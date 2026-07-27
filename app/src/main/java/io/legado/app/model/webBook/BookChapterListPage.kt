package io.legado.app.model.webBook

import android.text.TextUtils
import io.legado.app.constant.AppPattern
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.TocRule
import io.legado.app.help.config.AppConfig
import io.legado.app.model.Debug
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setChapter
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import io.legado.app.utils.isTrue
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Single-page TOC element parsing extracted from [BookChapterList].
 */
internal object BookChapterListPage {

    suspend fun parse(
        book: Book,
        baseUrl: String,
        redirectUrl: String,
        body: String,
        tocRule: TocRule,
        listRule: String,
        bookSource: BookSource,
        getNextUrl: Boolean = true,
        log: Boolean = false,
        isFromBookInfo: Boolean,
    ): Pair<List<BookChapter>, List<String>> {
        val analyzeRule = AnalyzeRule(book, bookSource, false, isFromBookInfo)
        analyzeRule.setContent(body).setBaseUrl(baseUrl)
        analyzeRule.setRedirectUrl(redirectUrl)
        analyzeRule.setCoroutineContext(currentCoroutineContext())
        val chapterList = arrayListOf<BookChapter>()
        Debug.log(bookSource.bookSourceUrl, "┌获取目录列表", log)
        val elements = analyzeRule.getElements(listRule)
        Debug.log(bookSource.bookSourceUrl, "└列表大小:${elements.size}", log)
        val nextUrlList = arrayListOf<String>()
        val nextTocRule = tocRule.nextTocUrl
        if (getNextUrl && !nextTocRule.isNullOrEmpty()) {
            Debug.log(bookSource.bookSourceUrl, "┌获取目录下一页列表", log)
            analyzeRule.getStringList(nextTocRule, isUrl = true)?.let {
                for (item in it) {
                    if (item != redirectUrl) nextUrlList.add(item)
                }
            }
            Debug.log(
                bookSource.bookSourceUrl,
                "└" + TextUtils.join("，\n", nextUrlList),
                log,
            )
        }
        currentCoroutineContext().ensureActive()
        if (elements.isEmpty()) {
            return Pair(chapterList, nextUrlList)
        }
        Debug.log(bookSource.bookSourceUrl, "┌解析目录列表", log)
        val nameRule = analyzeRule.splitSourceRule(tocRule.chapterName)
        val urlRule = analyzeRule.splitSourceRule(tocRule.chapterUrl)
        val vipRule = analyzeRule.splitSourceRule(tocRule.isVip)
        val payRule = analyzeRule.splitSourceRule(tocRule.isPay)
        val upTimeRule = analyzeRule.splitSourceRule(tocRule.updateTime)
        val isVolumeRule = analyzeRule.splitSourceRule(tocRule.isVolume)
        val tocCountWords = AppConfig.tocCountWords
        elements.forEachIndexed { index, item ->
            currentCoroutineContext().ensureActive()
            analyzeRule.setContent(item)
            val bookChapter = BookChapter(bookUrl = book.bookUrl, baseUrl = redirectUrl)
            analyzeRule.setChapter(bookChapter)
            bookChapter.title = analyzeRule.getString(nameRule)
            bookChapter.url = analyzeRule.getString(urlRule)
            val info = analyzeRule.getString(upTimeRule)
            val isVolume = analyzeRule.getString(isVolumeRule)
            bookChapter.isVolume = false
            if (isVolume.isTrue()) {
                bookChapter.isVolume = true
                bookChapter.tag = info
            } else if (tocCountWords) {
                AppPattern.wordCountRegex.find(info)?.let { match ->
                    bookChapter.wordCount = match.groupValues[1].trim()
                    bookChapter.tag = info.replaceFirst(match.value, "")
                } ?: run { bookChapter.tag = info }
            } else {
                bookChapter.tag = info
            }
            if (bookChapter.url.isEmpty()) {
                bookChapter.url = if (bookChapter.isVolume) {
                    bookChapter.title + index
                } else {
                    baseUrl
                }
            }
            if (bookChapter.title.isNotEmpty()) {
                if (analyzeRule.getString(vipRule).isTrue()) bookChapter.isVip = true
                if (analyzeRule.getString(payRule).isTrue()) bookChapter.isPay = true
                chapterList.add(bookChapter)
            }
        }
        Debug.log(bookSource.bookSourceUrl, "└目录列表解析完成", log)
        return Pair(chapterList, nextUrlList)
    }
}
