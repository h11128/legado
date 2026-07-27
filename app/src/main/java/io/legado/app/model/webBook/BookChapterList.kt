package io.legado.app.model.webBook

import com.script.ScriptBindings
import com.script.rhino.RhinoScriptEngine
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.exception.TocEmptyException
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.config.AppConfig
import io.legado.app.model.CheckMode
import io.legado.app.model.Debug
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.mapAsync
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.flow
import org.htmlunit.corejs.javascript.Context
import splitties.init.appCtx

/**
 * 获取目录
 */
object BookChapterList {

    suspend fun analyzeChapterList(
        bookSource: BookSource,
        book: Book,
        baseUrl: String,
        redirectUrl: String,
        body: String?,
        isFromBookInfo: Boolean = false,
    ): List<BookChapter> {
        body ?: throw NoStackTraceException(
            appCtx.getString(R.string.error_get_web_content, baseUrl)
        )
        val checkMode = CheckMode.current()
        val chapterList = ArrayList<BookChapter>()
        Debug.log(bookSource.bookSourceUrl, "≡获取成功:${baseUrl}")
        Debug.log(bookSource.bookSourceUrl, body, state = 30)
        val tocRule = bookSource.getTocRule()
        val nextUrlList = arrayListOf(redirectUrl)
        var reverse = false
        var listRule = tocRule.chapterList ?: ""
        if (listRule.startsWith("-")) {
            reverse = true
            listRule = listRule.substring(1)
        }
        if (listRule.startsWith("+")) {
            listRule = listRule.substring(1)
        }
        var chapterData = BookChapterListPage.parse(
            book, baseUrl, redirectUrl, body, tocRule, listRule, bookSource,
            log = true, isFromBookInfo = isFromBookInfo,
        )
        chapterList.addAll(chapterData.first)
        if (checkMode == null || !hasEnoughSample(chapterList, checkMode.tocSampleChapters)) {
            fetchMorePages(
                bookSource, book, tocRule, listRule, chapterData, nextUrlList, chapterList,
                isFromBookInfo, checkMode,
            )
        } else {
            Debug.log(bookSource.bookSourceUrl, "◇校验采样：首页已够 ${checkMode.tocSampleChapters} 章")
        }
        if (chapterList.isEmpty()) {
            throw TocEmptyException(appCtx.getString(R.string.chapter_list_empty))
        }
        if (!reverse) chapterList.reverse()
        currentCoroutineContext().ensureActive()
        val list = ArrayList(LinkedHashSet(chapterList))
        if (!book.getReverseToc()) list.reverse()
        Debug.log(book.origin, "◇目录总数:${list.size}")
        list.forEachIndexed { index, bookChapter -> bookChapter.index = index }
        if (checkMode == null) {
            applyFormatJs(tocRule.formatJs, list, book.origin)
            updateBookTocInfo(book, list)
        }
        return list
    }

    private suspend fun fetchMorePages(
        bookSource: BookSource,
        book: Book,
        tocRule: io.legado.app.data.entities.rule.TocRule,
        listRule: String,
        firstData: Pair<List<BookChapter>, List<String>>,
        nextUrlList: ArrayList<String>,
        chapterList: ArrayList<BookChapter>,
        isFromBookInfo: Boolean,
        checkMode: CheckMode?,
    ) {
        val maxPages = checkMode?.tocMaxPages ?: Int.MAX_VALUE
        when (firstData.second.size) {
            0 -> Unit
            1 -> {
                var nextUrl = firstData.second[0]
                var pages = 1
                while (
                    nextUrl.isNotEmpty() &&
                    !nextUrlList.contains(nextUrl) &&
                    pages < maxPages &&
                    (checkMode == null || !hasEnoughSample(chapterList, checkMode.tocSampleChapters))
                ) {
                    nextUrlList.add(nextUrl)
                    pages++
                    val analyzeUrl = AnalyzeUrl(
                        mUrl = nextUrl,
                        source = bookSource,
                        ruleData = book,
                        coroutineContext = currentCoroutineContext(),
                    )
                    val res = analyzeUrl.getStrResponseAwait()
                    res.body?.let { nextBody ->
                        val chapterData = BookChapterListPage.parse(
                            book, nextUrl, nextUrl, nextBody, tocRule, listRule, bookSource,
                            isFromBookInfo = isFromBookInfo,
                        )
                        nextUrl = chapterData.second.firstOrNull() ?: ""
                        chapterList.addAll(chapterData.first)
                    }
                }
                Debug.log(bookSource.bookSourceUrl, "◇目录总页数:${nextUrlList.size}")
            }
            else -> {
                val urls = if (checkMode != null) {
                    firstData.second.take((maxPages - 1).coerceAtLeast(0))
                } else {
                    firstData.second
                }
                if (urls.isEmpty()) return
                val concurrency = CheckMode.nestedConcurrency(AppConfig.threadCount)
                Debug.log(bookSource.bookSourceUrl, "◇并发解析目录,总页数:${urls.size},并发:$concurrency")
                flow { urls.forEach { emit(it) } }.mapAsync(concurrency) { urlStr ->
                    val analyzeUrl = AnalyzeUrl(
                        mUrl = urlStr,
                        source = bookSource,
                        ruleData = book,
                        coroutineContext = currentCoroutineContext(),
                    )
                    val res = analyzeUrl.getStrResponseAwait()
                    BookChapterListPage.parse(
                        book, urlStr, res.url, res.body!!, tocRule, listRule, bookSource,
                        getNextUrl = false, isFromBookInfo = isFromBookInfo,
                    ).first
                }.collect { chapterList.addAll(it) }
            }
        }
    }

    private fun hasEnoughSample(chapters: List<BookChapter>, need: Int): Boolean {
        var n = 0
        for (c in chapters) {
            if (!(c.isVolume && c.url.startsWith(c.title))) {
                if (++n >= need) return true
            }
        }
        return false
    }

    private fun applyFormatJs(formatJs: String?, list: ArrayList<BookChapter>, origin: String) {
        if (formatJs.isNullOrBlank()) return
        Context.enter().use {
            val bindings = ScriptBindings()
            bindings["gInt"] = 0
            list.forEachIndexed { index, bookChapter ->
                bindings["index"] = index + 1
                bindings["chapter"] = bookChapter
                bindings["title"] = bookChapter.title
                RhinoScriptEngine.runCatching {
                    eval(formatJs, bindings)?.toString()?.let { bookChapter.title = it }
                }.onFailure {
                    Debug.log(origin, "格式化标题出错, ${it.localizedMessage}")
                }
            }
        }
    }

    suspend fun updateBookTocInfo(book: Book, list: ArrayList<BookChapter>) {
        val replaceRules = ContentProcessor.get(book).getTitleReplaceRules()
        val replaceBook = book.toReplaceBook()
        book.durChapterTitle = list.getOrElse(book.durChapterIndex) { list.last() }
            .getDisplayTitle(replaceRules, book.getUseReplaceRule(), replaceBook = replaceBook)
        if (book.totalChapterNum < list.size) {
            book.lastCheckCount = list.size - book.totalChapterNum
            book.latestChapterTime = System.currentTimeMillis()
        }
        book.lastCheckTime = System.currentTimeMillis()
        book.totalChapterNum = list.size
        book.latestChapterTitle =
            list.getOrElse(book.simulatedTotalChapterNum() - 1) { list.last() }
                .getDisplayTitle(replaceRules, book.getUseReplaceRule(), replaceBook = replaceBook)
        currentCoroutineContext().ensureActive()
        if (!AppConfig.tocCountWords) return
        val saved = appDb.bookChapterDao.getChapterList(book.bookUrl)
        if (saved.isEmpty()) return
        val map = HashMap<String, Triple<String?, String?, String?>>(saved.size)
        for (chapter in saved) {
            map["${chapter.index}_${chapter.title}"] =
                Triple(chapter.wordCount, chapter.variable, chapter.imgUrl)
        }
        for (chapter in list) {
            map["${chapter.index}_${chapter.title}"]?.let { (w, v, i) ->
                w?.let { chapter.wordCount = it }
                v?.let { chapter.variable = it }
                i?.let { chapter.imgUrl = it }
            }
        }
    }
}
