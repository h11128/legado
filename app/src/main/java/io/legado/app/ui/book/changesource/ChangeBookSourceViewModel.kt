package io.legado.app.ui.book.changesource

import android.app.Application
import android.os.Bundle
import androidx.annotation.CallSuper
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.SearchBook
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.BookSourceTypeMapper
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.primaryStr
import io.legado.app.help.book.releaseHtmlData
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.SourceConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.source.SourceHelp
import io.legado.app.model.RespondTimeUpdater
import io.legado.app.model.checkalgo.AskSourceOrder
import io.legado.app.model.checkalgo.AskSourcePrefetch
import io.legado.app.model.checkalgo.AskTimeout
import io.legado.app.model.checkalgo.ChangeBookSourceQuality
import io.legado.app.model.checkalgo.ChangeChapterVerify
import io.legado.app.model.checkalgo.ChangeSourceAskMemory
import io.legado.app.model.checkalgo.ChangeSourceLog
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.internString
import io.legado.app.utils.mapParallel
import io.legado.app.utils.mapParallelSafe
import io.legado.app.utils.onEachIndexed
import io.legado.app.utils.runCatchingCancellable
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

@Suppress("MemberVisibilityCanBePrivate")
open class ChangeBookSourceViewModel(application: Application) : BaseViewModel(application) {
    private val threadCount = AppConfig.threadCount
    protected var searchPool: ExecutorCoroutineDispatcher? = null
    val searchStateData = MutableLiveData<Boolean>()
    var searchFinishCallback: ((isEmpty: Boolean) -> Unit)? = null
    var name: String = ""
    var author: String = ""
    private var fromReadBookActivity = false
    protected var oldBook: Book? = null
    private var screenKey: String = ""
    private var bookSourceParts = arrayListOf<BookSourcePart>()
    val totalSourceCount: Int
        get() = bookSourceParts.size
    private var searchBookList = arrayListOf<SearchBook>()
    protected val searchBooks = Collections.synchronizedList(arrayListOf<SearchBook>())
    protected val tocMap = ConcurrentHashMap<String, List<BookChapter>>()
    protected val _changeSourceProgress = MutableStateFlow(ChangeSourceProgressUi())
    val changeSourceProgress = _changeSourceProgress.asStateFlow()
    private var tocMapChapterCount = 0
    protected val contentProcessor by lazy {
        ContentProcessor.get(oldBook!!)
    }
    /** Cached once per 整书换源 search; cleared in [startSearch]. */
    private var wordCountEvalContext: ChangeChapterVerify.ContentEvalContext? = null
    /** OK chapter bodies for multi-source consensus (origin → text). */
    private val probeContentSamples = ConcurrentHashMap<String, String>()
    /** Meta quality tiers (latest / toc); worse wins via [ChangeBookSourceQuality.worseTier]. */
    private val qualityTiers = ConcurrentHashMap<String, Int>()
    /** Session-only soft fails (timeout / hijack); never written to BookSource.respondTime. */
    private val sessionSoftFail = ConcurrentHashMap.newKeySet<String>()
    /** In-flight probe names for progress subtitle (not “last completed”). */
    private val probingNames = ConcurrentHashMap.newKeySet<String>()
    private val completedProbeCount = AtomicInteger(0)
    private val qualityOkCount = AtomicInteger(0)
    private val earlyStopped = AtomicBoolean(false)
    protected var searchCallback: SourceCallback? = null
    protected var task: Job? = null
    val bookMap = ConcurrentHashMap<String, Book>()
    val searchDataFlow = callbackFlow {

        searchCallback = object : SourceCallback {

            override fun searchSuccess(searchBook: SearchBook) {
                searchBook.releaseHtmlData()
                appDb.searchBookDao.insert(searchBook)
                when {
                    screenKey.isEmpty() -> searchBooks.add(searchBook)
                    searchBook.name.contains(screenKey) -> searchBooks.add(searchBook)
                    else -> return
                }
                trySend(arrayOf(searchBooks))
            }

            override fun upAdapter() {
                trySend(arrayOf(searchBooks))
            }

        }

        getDbSearchBooks().let {
            searchBooks.clear()
            searchBooks.addAll(it)
            trySend(arrayOf(searchBooks))
        }

        if (searchBooks.isEmpty()) {
            startSearch()
        } else {
            onCachedSearchReady()
        }

        awaitClose {
            searchCallback = null
        }
    }.map {
        kotlin.runCatching {
            sortSearchBooks(searchBooks.toList())
        }.onFailure {
            AppLog.put("换源排序出错\n${it.localizedMessage}", it)
        }.getOrDefault(searchBooks)
    }.flowOn(IO)

    /** Called when DB already has searchBooks — book mode keeps list; chapter mode verifies. */
    protected open fun onCachedSearchReady() = Unit

    protected open fun sortSearchBooks(books: List<SearchBook>): List<SearchBook> {
        val expected = wordCountEvalContext?.expectedChars
        // Content first → length band → likes → probe respondTime → soft latest/TOC hint.
        return books.sortedWith(
            compareBy<SearchBook> {
                ChangeBookSourceQuality.contentSortTier(
                    chapterWordCount = it.chapterWordCount,
                    wordCountText = it.chapterWordCountText,
                    softFailed = it.origin in sessionSoftFail,
                )
            }
                .thenByDescending {
                    ChangeBookSourceQuality.lengthBandScore(it.chapterWordCount, expected)
                }
                .thenByDescending { getBookScore(it) }
                .thenByDescending { SourceConfig.getSourceScore(it.origin) }
                .thenBy {
                    ChangeBookSourceQuality.respondTimeSortKey(it.respondTime)
                }
                .thenBy {
                    ChangeBookSourceQuality.softMetaPenalty(
                        qualityTiers[it.origin] ?: ChangeBookSourceQuality.TIER_UNKNOWN
                    )
                }
                .thenBy { it.originOrder }
        )
    }

    /** Chapter used for word-count / content probe alignment. */
    protected open fun wordCountChapterIndex(chapters: List<BookChapter>): Int {
        return if (fromReadBookActivity) {
            BookHelp.getDurChapter(oldBook!!, chapters)
        } else {
            chapters.lastIndex
        }
    }

    protected open fun onSearchTaskFinished(isEmpty: Boolean) {
        searchFinishCallback?.invoke(isEmpty)
    }

    protected fun notifySearchAdapter() {
        searchCallback?.upAdapter()
    }

    protected fun putToc(book: Book, chapters: List<BookChapter>) {
        if (tocMapChapterCount < 30000) {
            tocMapChapterCount += chapters.size
            tocMap[book.primaryStr()] = chapters
        }
    }

    protected fun initSearchPoolProtected() = initSearchPool()

    protected fun updateChangeSourceProgress(index: Int, label: String) {
        _changeSourceProgress.value = ChangeSourceProgressUi(
            completed = index,
            label = label,
            qualityOk = qualityOkCount.get(),
            earlyStopped = earlyStopped.get(),
            finished = false,
        )
    }

    override fun onCleared() {
        super.onCleared()
        searchPool?.close()
    }

    @CallSuper
    open fun initData(arguments: Bundle?, book: Book?, fromReadBookActivity: Boolean) {
        arguments?.let { bundle ->
            bundle.getString("name")?.let {
                name = it
            }
            bundle.getString("author")?.let {
                author = it.replace(AppPattern.authorRegex, "")
            }
            this.fromReadBookActivity = fromReadBookActivity
            oldBook = book
        }
    }

    private fun initSearchPool() {
        // Reuse for ViewModel lifetime; closing/recreating each search leaked pools
        // after stopSearch stopped calling close(). onCleared() shuts it down.
        if (searchPool == null) {
            searchPool = Executors
                .newFixedThreadPool(min(threadCount, AppConst.MAX_THREAD))
                .asCoroutineDispatcher()
        }
    }

    open fun refresh(): Boolean {
        getDbSearchBooks().let {
            searchBooks.clear()
            searchBooks.addAll(it)
            searchCallback?.upAdapter()
        }
        return searchBooks.isEmpty()
    }

    /**
     * 搜索书籍
     */
    open fun startSearch() {
        execute {
            stopSearch()
            if (searchBooks.isNotEmpty()) {
                appDb.searchBookDao.delete(*searchBooks.toTypedArray())
                searchBooks.clear()
            }
            searchCallback?.upAdapter()
            bookSourceParts.clear()
            tocMap.clear()
            bookMap.clear()
            tocMapChapterCount = 0
            wordCountEvalContext = null
            probeContentSamples.clear()
            qualityTiers.clear()
            sessionSoftFail.clear()
            probingNames.clear()
            completedProbeCount.set(0)
            qualityOkCount.set(0)
            earlyStopped.set(false)
            _changeSourceProgress.value = ChangeSourceProgressUi()
            val searchGroup = AppConfig.searchGroup
            val loaded = if (searchGroup.isBlank()) {
                appDb.bookSourceDao.allEnabledPart
            } else {
                val sources = appDb.bookSourceDao.getEnabledPartByGroup(searchGroup)
                if (sources.isEmpty()) {
                    AppConfig.searchGroup = ""
                    appDb.bookSourceDao.allEnabledPart
                } else {
                    sources
                }
            }
            SourceHelp.ensureRespondTimeHealed()
            val typed = oldBook?.let {
                BookSourceTypeMapper.filterSameType(loaded, it.type)
            } ?: loaded
            bookSourceParts.addAll(
                AskSourceOrder.order(
                    typed,
                    threadCount = threadCount,
                    demoteUrls = ChangeSourceAskMemory.snapshot(),
                )
            )
            if (!AppConfig.changeSourceEarlyStop) {
                ChangeSourceLog.i("early-stop pref off; will ask all ${bookSourceParts.size} sources")
            }
            ChangeSourceLog.i(
                "start book=$name sources=${bookSourceParts.size} " +
                    "demoted=${ChangeSourceAskMemory.snapshot().size} " +
                    "loadWordCount=${AppConfig.changeSourceLoadWordCount} " +
                    "earlyStop=${AppConfig.changeSourceEarlyStop}"
            )
            initSearchPool()
            search()
        }
    }

    fun startSearch(origin: String) {
        execute {
            stopSearch()
            bookSourceParts.clear()
            tocMap.clear()
            bookMap.clear()
            tocMapChapterCount = 0
            wordCountEvalContext = null
            probeContentSamples.clear()
            qualityTiers.clear()
            sessionSoftFail.clear()
            probingNames.clear()
            completedProbeCount.set(0)
            qualityOkCount.set(0)
            earlyStopped.set(false)
            _changeSourceProgress.value = ChangeSourceProgressUi()
            bookSourceParts.add(appDb.bookSourceDao.getBookSourcePart(origin)!!)
            searchBooks.removeIf { it.origin == origin }
            initSearchPool()
            search()
        }
    }

    private fun noteAskMiss(bookSourceUrl: String, reason: String, processDemote: Boolean) {
        sessionSoftFail.add(bookSourceUrl)
        // Empty search for this book ≠ dead source — do not poison global ask-order.
        if (processDemote) {
            ChangeSourceAskMemory.noteMiss(bookSourceUrl)
        }
        ChangeSourceLog.i("miss $reason $bookSourceUrl processDemote=$processDemote")
    }

    private fun publishProgress(
        completed: Int = completedProbeCount.get(),
        early: Boolean = earlyStopped.get(),
        finished: Boolean = false,
    ) {
        val probing = probingNames.take(2).joinToString("、") { url ->
            bookSourceParts.find { it.bookSourceUrl == url }?.bookSourceName ?: url
        }
        val label = when {
            finished && early -> ""
            probing.isNotEmpty() -> "探测中 $probing"
            else -> ""
        }
        _changeSourceProgress.value = ChangeSourceProgressUi(
            completed = completed,
            label = label,
            qualityOk = qualityOkCount.get(),
            earlyStopped = early,
            finished = finished,
        )
    }

    private fun search() {
        val parts = bookSourceParts.toList()
        task = viewModelScope.launch(searchPool!!) {
            AskSourcePrefetch.emitSources(parts).onStart {
                searchStateData.postValue(true)
                publishProgress()
            }.mapParallel(threadCount) { source ->
                if (earlyStopped.get()) return@mapParallel source
                if (source.bookSourceUrl in sessionSoftFail) return@mapParallel source
                probingNames.add(source.bookSourceUrl)
                publishProgress()
                try {
                    search(source)
                } catch (e: TimeoutCancellationException) {
                    // Session demote only — RFC-001 forbids persisting failure on ask timeout.
                    noteAskMiss(source.bookSourceUrl, "timeout", processDemote = true)
                    currentCoroutineContext().ensureActive()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    noteAskMiss(source.bookSourceUrl, "error", processDemote = true)
                    currentCoroutineContext().ensureActive()
                } finally {
                    probingNames.remove(source.bookSourceUrl)
                    completedProbeCount.incrementAndGet()
                    publishProgress()
                }
                source
            }.onEachIndexed { _, _ ->
                if (ChangeBookSourceQuality.shouldEarlyStop(
                        qualityOkCount = qualityOkCount.get(),
                        enabled = AppConfig.changeSourceEarlyStop,
                        target = AppConfig.changeSourceEarlyStopCount,
                    )
                ) {
                    if (earlyStopped.compareAndSet(false, true)) {
                        ChangeSourceLog.i(
                            "early-stop qualityOk=${qualityOkCount.get()} " +
                                "target=${AppConfig.changeSourceEarlyStopCount}"
                        )
                        publishProgress(early = true)
                        currentCoroutineContext().cancel()
                    }
                }
            }.onCompletion { cause ->
                withContext(NonCancellable + IO) {
                    applyBookQualityGates(force = true)
                    RespondTimeUpdater.flush()
                    probingNames.clear()
                    publishProgress(
                        early = earlyStopped.get(),
                        finished = true,
                    )
                    searchStateData.postValue(false)
                    // User cancel / restart: do not invoke finish callback (avoids false empty toast).
                    if (cause == null || earlyStopped.get()) {
                        onSearchTaskFinished(searchBooks.isEmpty())
                    }
                }
            }.catch {
                ChangeSourceLog.w("搜索出错 ${it.localizedMessage}", it)
                AppLog.put("换源搜索出错\n${it.localizedMessage}", it)
            }.collect()
        }
    }

    private suspend fun search(source: BookSource) {
        val checkAuthor = AppConfig.changeSourceCheckAuthor
        val loadInfo = AppConfig.changeSourceLoadInfo
        val loadToc = AppConfig.changeSourceLoadToc
        val loadWordCount = AppConfig.changeSourceLoadWordCount
        val startTime = System.currentTimeMillis()
        // withTimeoutOrNull returns promptly even when nested Cronet/WebView cleanup is slow.
        val ok = withTimeoutOrNull(AskTimeout.CHANGE_SOURCE_MS) {
            val resultBooks = WebBook.searchBookAwait(
                source, name,
                filter = { fName, fAuthor, _ ->
                    fName == name && (!checkAuthor || fAuthor.contains(author))
                })
            currentCoroutineContext().ensureActive()
            val searchElapsed = System.currentTimeMillis() - startTime
            if (resultBooks.isEmpty()) {
                noteAskMiss(source.bookSourceUrl, "empty", processDemote = false)
                return@withTimeoutOrNull true
            }
            RespondTimeUpdater.noteSuccessAndMaybeFlush(
                source.bookSourceUrl,
                searchElapsed,
                source.respondTime,
            )
            if (loadInfo || loadToc || loadWordCount) {
                resultBooks.forEach { searchBook ->
                    currentCoroutineContext().ensureActive()
                    loadBookInfo(source, searchBook.toBook())
                }
            } else {
                resultBooks.forEach { searchBook ->
                    publishSearchBook(searchBook)
                }
            }
            true
        }
        if (ok != true) {
            noteAskMiss(source.bookSourceUrl, "timeout", processDemote = true)
        }
    }

    private suspend fun loadBookInfo(source: BookSource, book: Book) {
        if (book.tocUrl.isEmpty()) {
            WebBook.getBookInfoAwait(source, book)
        }
        if (AppConfig.changeSourceLoadToc || AppConfig.changeSourceLoadWordCount) {
            loadBookToc(source, book)
        } else {
            //从详情页里获取最新章节
            publishSearchBook(book.toSearchBook())
        }
    }

    private suspend fun loadBookToc(source: BookSource, book: Book) {
        val chapters = WebBook.getChapterListAwait(source, book).getOrThrow()
        for (chapter in chapters) {
            chapter.internString()
        }
        putToc(book, chapters)
        bookMap[book.primaryStr()] = book
        book.releaseHtmlData()
        if (AppConfig.changeSourceLoadWordCount) {
            loadBookWordCount(source, book, chapters)
        } else {
            publishSearchBook(book.toSearchBook(), tocSize = chapters.size)
        }
    }

    private suspend fun loadBookWordCount(
        source: BookSource,
        book: Book,
        chapters: List<BookChapter>
    ) = coroutineScope {
        if (chapters.isEmpty()) {
            val searchBook = book.toSearchBook().apply {
                chapterWordCountText = "目录为空"
                chapterWordCount = -1
            }
            publishSearchBook(searchBook, tocSize = 0)
            return@coroutineScope
        }
        val chapterIndex = wordCountChapterIndex(chapters).coerceIn(0, chapters.lastIndex)
        val bookChapter = chapters[chapterIndex]
        var title = bookChapter.title.trim()
        if (title.length > 20) {
            title = title.substring(0, 20) + "…"
        }
        val startTime = System.currentTimeMillis()
        var processedContent: String? = null
        val pair = try {
            val nextChapterUrl = chapters.getOrNull(chapterIndex + 1)?.url
            var content = WebBook.getContentAwait(source, book, bookChapter, nextChapterUrl, false)
            content = contentProcessor.getContent(oldBook!!, bookChapter, content, false).toString()
            processedContent = content
            // Same structural gates as 单章换源: length / stitch / optional local reference.
            when (val quality = ChangeChapterVerify.evaluateContent(
                content,
                bookChangeContentEvalContext(chapterIndex, bookChapter.title),
            )) {
                is ChangeChapterVerify.ContentQuality.Ok -> {
                    quality.length to "[${chapterIndex + 1}] ${title}\n字数：${quality.length}"
                }
                ChangeChapterVerify.ContentQuality.TooShort -> {
                    -1 to "[${chapterIndex + 1}] ${title}\n" +
                        getApplication<Application>().getString(R.string.change_source_chapter_too_short)
                }
                ChangeChapterVerify.ContentQuality.AntiTheft -> {
                    -1 to "[${chapterIndex + 1}] ${title}\n" +
                        getApplication<Application>().getString(R.string.change_source_chapter_anti_theft)
                }
                ChangeChapterVerify.ContentQuality.Hijack -> {
                    -1 to "[${chapterIndex + 1}] ${title}\n" +
                        getApplication<Application>().getString(R.string.change_source_chapter_hijack)
                }
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            -1 to "[${chapterIndex + 1}] ${title}\n获取字数失败：${t.localizedMessage}"
        }
        val endTime = System.currentTimeMillis()
        val searchBook = book.toSearchBook().apply {
            chapterWordCountText = pair.second
            chapterWordCount = pair.first
            respondTime = (endTime - startTime).toInt()
        }
        if (ChangeBookSourceQuality.isQualityOkWordCount(pair.first) && processedContent != null) {
            probeContentSamples[searchBook.origin] = processedContent
            qualityOkCount.incrementAndGet()
        } else if (pair.first < 0) {
            noteAskMiss(searchBook.origin, "content-bad", processDemote = true)
            mergeTier(searchBook.origin, ChangeBookSourceQuality.TIER_CONTENT_BAD)
        }
        publishSearchBook(searchBook, tocSize = chapters.size)
        applyBookQualityGates(force = false)
    }

    private fun publishSearchBook(searchBook: SearchBook, tocSize: Int? = null) {
        annotateMetaQuality(searchBook, tocSize)
        searchCallback?.searchSuccess(searchBook)
    }

    private fun annotateMetaQuality(searchBook: SearchBook, tocSize: Int?) {
        val origin = searchBook.origin
        val local = oldBook
        if (tocSize != null &&
            local != null &&
            !ChangeBookSourceQuality.tocConsistent(local.totalChapterNum, tocSize)
        ) {
            mergeTier(origin, ChangeBookSourceQuality.TIER_TOC_BAD)
            searchBook.chapterWordCountText = appendBadge(
                searchBook.chapterWordCountText,
                getApplication<Application>().getString(R.string.change_source_toc_mismatch),
            )
        }
        when (ChangeBookSourceQuality.latestMatchesLocal(
            local?.latestChapterTitle,
            searchBook.latestChapterTitle,
        )) {
            false -> {
                mergeTier(origin, ChangeBookSourceQuality.TIER_LATEST_BAD)
                searchBook.chapterWordCountText = appendBadge(
                    searchBook.chapterWordCountText,
                    getApplication<Application>().getString(R.string.change_source_latest_mismatch),
                )
            }
            else -> Unit
        }
    }

    private fun applyBookQualityGates(force: Boolean) {
        val samples = probeContentSamples.toMap()
        if (force || samples.size >= ChangeChapterVerify.MULTI_SOURCE_MIN_SAMPLES) {
            val outliers = ChangeChapterVerify.multiSourceOutlierOrigins(
                samples = samples,
                referenceContent = wordCountEvalContext?.referenceContent,
            )
            for (origin in outliers) {
                demoteOriginContent(
                    origin,
                    getApplication<Application>().getString(R.string.change_source_chapter_hijack),
                )
            }
        }
        val titles = searchBooks.mapNotNull { book ->
            book.latestChapterTitle?.trim()?.takeIf { it.isNotEmpty() }?.let { book.origin to it }
        }.toMap()
        if (force || titles.size >= ChangeChapterVerify.MULTI_SOURCE_MIN_SAMPLES) {
            val latestOutliers = ChangeBookSourceQuality.latestTitleOutliers(
                titlesByOrigin = titles,
                localLatest = oldBook?.latestChapterTitle,
            )
            for (origin in latestOutliers) {
                mergeTier(origin, ChangeBookSourceQuality.TIER_LATEST_BAD)
                searchBooks.find { it.origin == origin }?.let { book ->
                    book.chapterWordCountText = appendBadge(
                        book.chapterWordCountText,
                        getApplication<Application>().getString(R.string.change_source_latest_mismatch),
                    )
                }
            }
        }
        if (force || titles.size >= ChangeChapterVerify.MULTI_SOURCE_MIN_SAMPLES ||
            samples.size >= ChangeChapterVerify.MULTI_SOURCE_MIN_SAMPLES
        ) {
            searchCallback?.upAdapter()
        }
    }

    private fun demoteOriginContent(origin: String, badge: String) {
        sessionSoftFail.add(origin)
        mergeTier(origin, ChangeBookSourceQuality.TIER_CONTENT_BAD)
        val removedSample = probeContentSamples.remove(origin) != null
        searchBooks.find { it.origin == origin }?.let { book ->
            val wasOk = ChangeBookSourceQuality.isQualityOkWordCount(book.chapterWordCount)
            book.chapterWordCount = -1
            book.chapterWordCountText = appendBadge(book.chapterWordCountText, badge)
            if (removedSample || wasOk) {
                qualityOkCount.updateAndGet { (it - 1).coerceAtLeast(0) }
            }
        } ?: run {
            if (removedSample) {
                qualityOkCount.updateAndGet { (it - 1).coerceAtLeast(0) }
            }
        }
    }

    private fun mergeTier(origin: String, tier: Int) {
        qualityTiers[origin] = ChangeBookSourceQuality.worseTier(
            qualityTiers[origin] ?: ChangeBookSourceQuality.TIER_UNKNOWN,
            tier,
        )
    }

    private fun appendBadge(existing: String?, badge: String): String {
        val base = existing?.trim().orEmpty()
        if (base.contains(badge)) return base
        return if (base.isEmpty()) badge else "$base\n$badge"
    }

    /**
     * Local chapter body aligned to the candidate chapter being probed.
     * Cached for the search session (same logical chapter for from-read probes).
     * If disk cache is empty, one-shot fetch from the book's current origin.
     */
    private suspend fun bookChangeContentEvalContext(
        candidateChapterIndex: Int,
        candidateTitle: String,
    ): ChangeChapterVerify.ContentEvalContext {
        wordCountEvalContext?.let { return it }
        val book = oldBook ?: return ChangeChapterVerify.ContentEvalContext()
            .also { wordCountEvalContext = it }
        val localChapters = appDb.bookChapterDao.getChapterList(book.bookUrl)
        if (localChapters.isEmpty()) {
            return ChangeChapterVerify.ContentEvalContext().also { wordCountEvalContext = it }
        }
        val idx = ChangeChapterVerify.alignIndex(
            candidateChapterIndex,
            candidateTitle,
            localChapters,
        ) ?: wordCountChapterIndex(localChapters).coerceIn(0, localChapters.lastIndex)
        val localChapter = localChapters[idx]
        var reference = BookHelp.getContent(book, localChapter)?.trim()?.takeIf { it.isNotEmpty() }
        if (reference == null) {
            val origin = appDb.bookSourceDao.getBookSource(book.origin)
            if (origin != null) {
                reference = runCatchingCancellable {
                    val nextUrl = localChapters.getOrNull(idx + 1)?.url
                    val raw = WebBook.getContentAwait(origin, book, localChapter, nextUrl, false)
                    contentProcessor.getContent(book, localChapter, raw, false).toString()
                }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            }
        }
        return ChangeChapterVerify.ContentEvalContext(
            expectedChars = reference?.length?.takeIf {
                it >= ChangeChapterVerify.MIN_CONTENT_CHARS
            },
            referenceContent = reference,
        ).also { wordCountEvalContext = it }
    }

    fun onLoadWordCountChecked(isChecked: Boolean) {
        if (isChecked) {
            startRefreshList(true)
        }
    }

    /**
     * 刷新列表
     */
    fun startRefreshList(onlyRefreshNoWordCountBook: Boolean = false) {
        execute {
            stopSearch()
            searchBookList.clear()
            if (onlyRefreshNoWordCountBook) {
                searchBooks.filterTo(searchBookList) {
                    it.chapterWordCountText == null
                }
                searchBooks.removeIf { it.chapterWordCountText == null }
            } else {
                searchBookList.addAll(searchBooks)
                searchBooks.clear()
            }
            searchCallback?.upAdapter()
            initSearchPool()
            refreshList()
        }
    }

    private fun refreshList() {
        task = viewModelScope.launch(searchPool!!) {
            flow {
                for (searchBook in searchBookList) {
                    emit(searchBook)
                }
            }.onStart {
                searchStateData.postValue(true)
            }.mapParallelSafe(threadCount) {
                val source = appDb.bookSourceDao.getBookSource(it.origin)!!
                withTimeout(AskTimeout.CHANGE_SOURCE_MS) {
                    loadBookInfo(source, it.toBook())
                }
            }.onCompletion {
                searchStateData.postValue(false)
            }.catch {
                AppLog.put("换源刷新列表出错\n${it.localizedMessage}", it)
            }.collect()
        }
    }

    private fun getDbSearchBooks(): List<SearchBook> {
        return if (screenKey.isEmpty()) {
            if (AppConfig.changeSourceCheckAuthor) {
                appDb.searchBookDao.changeSourceByGroup(
                    name, author, AppConfig.searchGroup
                )
            } else {
                appDb.searchBookDao.changeSourceByGroup(
                    name, "", AppConfig.searchGroup
                )
            }
        } else {
            if (AppConfig.changeSourceCheckAuthor) {
                appDb.searchBookDao.changeSourceSearch(
                    name, author, screenKey, AppConfig.searchGroup
                )
            } else {
                appDb.searchBookDao.changeSourceSearch(
                    name, "", screenKey, AppConfig.searchGroup
                )
            }
        }
    }

    /**
     * 筛选
     */
    fun screen(key: String?) {
        screenKey = key?.trim() ?: ""
        execute {
            getDbSearchBooks().let {
                searchBooks.clear()
                searchBooks.addAll(it)
                searchCallback?.upAdapter()
            }
        }
    }

    fun startOrStopSearch() {
        if (task == null || !task!!.isActive) {
            startSearch()
        } else {
            stopSearch()
        }
    }

    fun stopSearch() {
        task?.cancel()
        // Do not close searchPool here: onCompletion may still flush on it;
        // initSearchPool only creates when null, onCleared() closes the pool.
        searchStateData.postValue(false)
    }

    fun getToc(
        book: Book,
        onSuccess: (toc: List<BookChapter>, source: BookSource) -> Unit,
        onError: (e: Throwable) -> Unit
    ): Coroutine<Pair<List<BookChapter>, BookSource>> {
        return execute {
            val toc = tocMap[book.primaryStr()]
            if (toc != null) {
                val source = appDb.bookSourceDao.getBookSource(book.origin)
                return@execute Pair(toc, source!!)
            }
            val result = getToc(book).getOrThrow()
            tocMap[book.primaryStr()] = result.first
            return@execute result
        }.onSuccess {
            onSuccess.invoke(it.first, it.second)
        }.onError {
            onError.invoke(it)
        }
    }

    suspend fun getToc(book: Book): Result<Pair<List<BookChapter>, BookSource>> {
        return runCatchingCancellable {
            val source = appDb.bookSourceDao.getBookSource(book.origin)
                ?: throw NoStackTraceException("书源不存在")
            if (book.tocUrl.isEmpty()) {
                WebBook.getBookInfoAwait(source, book)
            }
            val toc = WebBook.getChapterListAwait(source, book).getOrThrow()
            Pair(toc, source)
        }
    }

    fun disableSource(searchBook: SearchBook) {
        execute {
            appDb.bookSourceDao.getBookSource(searchBook.origin)?.let { source ->
                source.enabled = false
                appDb.bookSourceDao.update(source)
            }
            searchBooks.remove(searchBook)
            searchCallback?.upAdapter()
        }
    }

    fun topSource(searchBook: SearchBook) {
        execute {
            appDb.bookSourceDao.getBookSource(searchBook.origin)?.let { source ->
                val minOrder = appDb.bookSourceDao.minOrder - 1
                source.customOrder = minOrder
                searchBook.originOrder = source.customOrder
                appDb.bookSourceDao.update(source)
                updateSource(searchBook)
            }
            searchCallback?.upAdapter()
        }
    }

    fun bottomSource(searchBook: SearchBook) {
        execute {
            appDb.bookSourceDao.getBookSource(searchBook.origin)?.let { source ->
                val maxOrder = appDb.bookSourceDao.maxOrder + 1
                source.customOrder = maxOrder
                searchBook.originOrder = source.customOrder
                appDb.bookSourceDao.update(source)
                updateSource(searchBook)
            }
            searchCallback?.upAdapter()
        }
    }

    fun updateSource(searchBook: SearchBook) {
        appDb.searchBookDao.update(searchBook)
    }

    fun del(searchBook: SearchBook) {
        execute {
            SourceHelp.deleteBookSource(searchBook.origin)
            appDb.searchBookDao.delete(searchBook)
        }
        searchBooks.remove(searchBook)
        searchCallback?.upAdapter()
    }

    fun autoChangeSource(
        bookType: Int?,
        onSuccess: (book: Book, toc: List<BookChapter>, source: BookSource) -> Unit
    ) {
        execute {
            searchBooks.forEach {
                if (it.type == bookType) {
                    val book = it.toBook()
                    val result = getToc(book).getOrNull()
                    if (result != null) {
                        return@execute Triple(book, result.first, result.second)
                    }
                }
            }
            throw NoStackTraceException("没有有效源")
        }.onSuccess {
            onSuccess.invoke(it.first, it.second, it.third)
        }.onError {
            context.toastOnUi("自动换源失败\n${it.localizedMessage}")
        }
    }

    fun setBookScore(searchBook: SearchBook, score: Int) {
        execute {
            SourceConfig.setBookScore(searchBook.origin, searchBook.name, searchBook.author, score)
            searchCallback?.upAdapter()
        }
    }

    fun getBookScore(searchBook: SearchBook): Int {
        return SourceConfig.getBookScore(searchBook.origin, searchBook.name, searchBook.author)
    }

    interface SourceCallback {

        fun searchSuccess(searchBook: SearchBook)

        fun upAdapter()

    }

}
