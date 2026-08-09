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
import io.legado.app.model.checkalgo.AskTimeoutBudget
import io.legado.app.model.checkalgo.ChangeBookSourceQuality
import io.legado.app.model.checkalgo.ChangeChapterVerify
import io.legado.app.model.checkalgo.ChangeSourceAskMemory
import io.legado.app.model.checkalgo.ChangeSourceLog
import io.legado.app.model.checkalgo.CheckAlgoRuntime
import io.legado.app.model.checkalgo.CheckHostTokenBucket
import io.legado.app.help.config.ChangeSourceTitleEmptyPrefs
import io.legado.app.help.http.configureCheckHttpLimits
import io.legado.app.help.http.HttpCallTiming
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.restoreDefaultHttpLimits
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.internString
import io.legado.app.utils.mapParallel
import io.legado.app.utils.mapParallelSafe
import io.legado.app.utils.onEachIndexed
import io.legado.app.utils.runCatchingCancellable
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
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
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

@Suppress("MemberVisibilityCanBePrivate")
open class ChangeBookSourceViewModel(application: Application) : BaseViewModel(application) {
    /**
     * Live 「更新和搜索线程数」— never cache at ViewModel init (prefs may change).
     */
    protected fun threadCount(): Int =
        AppConfig.threadCount.coerceIn(1, AppConst.MAX_THREAD)

    protected var searchPool: CoroutineDispatcher? = null
    private var searchPoolSize: Int = 0
    private val lastProgressPublishMs = AtomicLong(0L)
    val searchStateData = MutableLiveData<Boolean>()
    var searchFinishCallback: ((isEmpty: Boolean) -> Unit)? = null
    var name: String = ""
    var author: String = ""
    private var fromReadBookActivity = false
    protected var oldBook: Book? = null
    private var referenceWordCount: Int? = null
    private var relativeFilterWarningShown = false
    private var screenKey: String = ""
    private var bookSourceParts = arrayListOf<BookSourcePart>()
    val totalSourceCount: Int
        get() = bookSourceParts.size
    protected val searchBooks = Collections.synchronizedList(arrayListOf<SearchBook>())
    protected val tocMap = ConcurrentHashMap<String, List<BookChapter>>()
    protected val _changeSourceProgress = MutableStateFlow(ChangeSourceProgressUi())
    val changeSourceProgress = _changeSourceProgress.asStateFlow()
    private var tocMapChapterCount = 0
    protected val contentProcessor by lazy {
        ContentProcessor.get(oldBook!!)
    }
    /** Cached once per 整书换源 search; cleared in [startSearch]. Prefer dur-chapter ref. */
    private var wordCountEvalContext: ChangeChapterVerify.ContentEvalContext? = null
    /** Reference keyed by aligned local chapter index (do not lock first wrong hit). */
    private val wordCountEvalByLocalIndex =
        ConcurrentHashMap<Int, ChangeChapterVerify.ContentEvalContext>()
    /** OK chapter bodies for multi-source consensus (origin → text). */
    private val probeContentSamples = ConcurrentHashMap<String, String>()
    /** Meta quality tiers (latest / toc); worse wins via [ChangeBookSourceQuality.worseTier]. */
    private val qualityTiers = ConcurrentHashMap<String, Int>()
    /** Session-only soft fails (timeout / hijack); never written to BookSource.respondTime. */
    private val sessionSoftFail = ConcurrentHashMap.newKeySet<String>()
    /** In-flight ask (search) names for progress subtitle. */
    private val probingNames = ConcurrentHashMap.newKeySet<String>()
    /** Hits still in toc/content after ask slot released. */
    private val deepInFlightNames = ConcurrentHashMap.newKeySet<String>()
    /** Last content digram Jaccard vs local ref (origin → sim); used to suppress tip badges. */
    private val contentRefSimByOrigin = ConcurrentHashMap<String, Double>()
    private val completedProbeCount = AtomicInteger(0)
    private val qualityOkCount = AtomicInteger(0)
    private val earlyStopped = AtomicBoolean(false)
    /** Session counters for finish summary (logcat / AppLog). */
    private val searchHitCount = AtomicInteger(0)
    private val listPublishCount = AtomicInteger(0)
    private val missEmptyCount = AtomicInteger(0)
    private val missTimeoutCount = AtomicInteger(0)
    private val missErrorCount = AtomicInteger(0)
    private val missContentBadCount = AtomicInteger(0)
    private val lastProgressLogCompleted = AtomicInteger(-1)
    private val deepJobs = ConcurrentHashMap.newKeySet<Job>()
    protected var searchCallback: SourceCallback? = null
    protected var task: Job? = null
    private var deepPool: CoroutineDispatcher? = null
    private var deepPoolSize: Int = 0
    /**
     * Hard cap on concurrent deep probes (including suspended network).
     * [Dispatchers.IO.limitedParallelism] alone is not enough: permits release on
     * suspend, so deepInFlight can overshoot the labeled /N cap (self-test 2026-08-05).
     */
    private var deepGate: Semaphore? = null
    /**
     * Generation for shared OkHttp dispatcher raise/restore.
     * Old search onCompletion must not restore after a newer [raiseHttpLimitsForSearch].
     */
    private val httpLimitsEpoch = AtomicInteger(0)
    /** Per-host ask pacing (RFC-002 companion) — same defaults as bulk check. */
    private var askHostBucket: CheckHostTokenBucket? = null
    private val operationState = ChangeSourceOperationState()
    private val operationPreparation = Mutex()
    val bookMap = ConcurrentHashMap<String, Book>()
    val searchDataFlow = callbackFlow {

        searchCallback = object : SourceCallback {

            override fun searchSuccess(searchBook: SearchBook) {
                // Early-stop: never re-accept unfinished deep pending after dropPending.
                val pendingLabel =
                    getApplication<Application>().getString(R.string.change_source_pending_word)
                if (earlyStopped.get() &&
                    searchBook.chapterWordCount == 0 &&
                    searchBook.chapterWordCountText == pendingLabel
                ) {
                    return
                }
                // Keep all title hits in memory/DB; menu filters apply in currentResults().
                ChangeBookSourceQuality.decorateSearchHitForChangeSource(searchBook)
                searchBook.releaseHtmlData()
                appDb.searchBookDao.insert(searchBook)
                val accepted = synchronized(searchBooks) {
                    // bookUrl (not origin): aggregators may return several backends.
                    val idx = searchBooks.indexOfFirst { it.bookUrl == searchBook.bookUrl }
                    when {
                        idx >= 0 -> {
                            searchBooks[idx] = searchBook
                            true
                        }
                        else -> {
                            // Keep every title hit; screenKey is display-only in currentResults().
                            searchBooks.add(searchBook)
                            true
                        }
                    }
                }
                if (!accepted) return
                val size = searchBooks.size
                listPublishCount.incrementAndGet()
                val visible = passesDisplayFilters(searchBook)
                val tier = ChangeBookSourceQuality.contentSortTier(
                    chapterWordCount = searchBook.chapterWordCount,
                    wordCountText = searchBook.chapterWordCountText,
                    softFailed = searchBook.origin in sessionSoftFail,
                    verdict = searchBook.qualityVerdict,
                )
                ChangeSourceLog.i(
                    "list+ size=$size visible=$visible origin=${searchBook.origin} " +
                        "name=${searchBook.originName} words=${searchBook.chapterWordCount} " +
                        "verdict=${searchBook.qualityVerdict} score=${searchBook.smartScore} " +
                        "tier=$tier respondMs=${searchBook.respondTime} " +
                        "latest=${searchBook.latestChapterTitle?.take(24) ?: ""}"
                )
                trySend(arrayOf(searchBooks))
            }

            override fun upAdapter() {
                trySend(arrayOf(searchBooks))
            }

        }

        getDbSearchBooks().let { rows ->
            searchBooks.clear()
            // Keep all cached hits; display filters run in currentResults().
            rows.forEach { ChangeBookSourceQuality.decorateSearchHitForChangeSource(it) }
            searchBooks.addAll(rows)
            trySend(arrayOf(searchBooks))
        }

        when {
            searchBooks.isEmpty() -> startSearch()
            AppConfig.changeSourceLoadWordCount -> startRefreshList(true)
            else -> onCachedSearchReady()
        }

        awaitClose {
            searchCallback = null
        }
    }.map {
        kotlin.runCatching {
            currentResults()
        }.onFailure {
            AppLog.put("换源排序出错\n${it.localizedMessage}", it)
        }.getOrDefault(searchBooks)
    }.flowOn(IO)

    /** Sorted/filtered snapshot for UI + autoChangeSource (same policy). */
    private fun currentResults(): List<SearchBook> {
        val books = synchronized(searchBooks) {
            searchBooks.filter { passesDisplayFilters(it) }.toList()
        }
        return sortSearchBooks(books)
    }

    /** Menu filters (author / non-novel host / dict intro / content-bad) — display only. */
    private fun passesDisplayFilters(book: SearchBook): Boolean {
        if (screenKey.isNotEmpty()) {
            val key = screenKey
            val screenHit = book.originName.contains(key) ||
                book.author.contains(key) ||
                (book.latestChapterTitle?.contains(key) == true)
            if (!screenHit) return false
        }
        if (AppConfig.changeSourceDropContentBad &&
            ChangeBookSourceQuality.isContentBadVerdict(book.qualityVerdict)
        ) {
            return false
        }
        // Legacy cached rows: measured failure used chapterWordCount=-1 + failure text.
        if (AppConfig.changeSourceDropContentBad &&
            book.qualityVerdict == null &&
            book.chapterWordCount == -1 &&
            !book.chapterWordCountText.isNullOrBlank()
        ) {
            return false
        }
        return isAcceptableChangeSourceHit(
            book,
            requireAuthor = AppConfig.changeSourceCheckAuthor,
        )
    }

    /** Menu toggled a display filter — reshuffle visible rows without re-searching. */
    fun onDisplayFilterPrefsChanged() {
        searchCallback?.upAdapter()
    }

    /** Called when DB already has searchBooks — book mode keeps list; chapter mode verifies. */
    protected open fun onCachedSearchReady() = Unit

    protected open fun sortSearchBooks(books: List<SearchBook>): List<SearchBook> {
        val expected = wordCountEvalContext?.expectedChars
        // Smart score first → content tier → length band → likes → respondTime → soft meta.
        val qualityComparator = compareByDescending<SearchBook> {
            ChangeBookSourceQuality.sortSmartScoreKey(it.smartScore, it.qualityVerdict)
        }
            .thenBy {
                ChangeBookSourceQuality.contentSortTier(
                    chapterWordCount = it.chapterWordCount,
                    wordCountText = it.chapterWordCountText,
                    softFailed = it.origin in sessionSoftFail,
                    verdict = it.qualityVerdict,
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
                    qualityTiers[it.bookUrl]
                        ?: qualityTiers[it.origin]
                        ?: ChangeBookSourceQuality.TIER_UNKNOWN,
                    chapterWordCount = it.chapterWordCount,
                    verdict = it.qualityVerdict,
                )
            }
            .thenBy { it.originOrder }
        val filterMode = if (AppConfig.changeSourceLoadWordCount) {
            AppConfig.changeSourceWordCountFilterMode
        } else {
            ChangeSourceResultOptions.FILTER_OFF
        }
        val comparator = when {
            AppConfig.changeSourceSortRespondTime ->
                ChangeSourceResultOptions.responseTimeComparator(qualityComparator)

            filterMode != ChangeSourceResultOptions.FILTER_OFF ->
                ChangeSourceResultOptions.measuredFirstComparator(qualityComparator)

            else -> qualityComparator
        }
        return ChangeSourceResultOptions.apply(
            books = books,
            filterMode = filterMode,
            minimum = AppConfig.changeSourceWordCountFilterMin,
            maximum = AppConfig.changeSourceWordCountFilterMax,
            referenceWordCount = getReferenceWordCount(books),
            comparator = comparator,
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
            inFlight = 0,
            concurrency = threadCount(),
            deepInFlight = deepInFlightNames.size,
            label = label,
            qualityOk = qualityOkCount.get(),
            hitCount = searchHitCount.get(),
            earlyStopped = earlyStopped.get(),
            finished = false,
        )
    }

    override fun onCleared() {
        super.onCleared()
        // Force restore: no further search owns the raised caps.
        if (httpLimitsEpoch.get() > 0) {
            restoreDefaultHttpLimits()
            ChangeSourceLog.i("http-limits restored onCleared")
        }
        (searchPool as? Closeable)?.close()
        searchPool = null
        searchPoolSize = 0
        (deepPool as? Closeable)?.close()
        deepPool = null
        deepPoolSize = 0
        deepGate = null
    }

    /**
     * Match [io.legado.app.service.CheckSourceService]: with threadCount≈100, default
     * OkHttp maxRequests=64 makes deep getContent sit in the dispatcher queue behind asks,
     * so UI「响应时间」shows 40–60s even when the chapter body itself is a 2–5s fetch.
     * @return epoch that must be passed to [restoreHttpLimitsIfNeeded]
     */
    private fun raiseHttpLimitsForSearch(): Int {
        val threads = threadCount()
        val epoch = httpLimitsEpoch.incrementAndGet()
        configureCheckHttpLimits(
            maxRequests = (threads * 2).coerceAtMost(256),
            maxRequestsPerHost = 8,
        )
        val d = okHttpClient.dispatcher
        ChangeSourceLog.i(
            "http-limits raised epoch=$epoch maxRequests=${d.maxRequests} " +
                "perHost=${d.maxRequestsPerHost} threads=$threads"
        )
        return epoch
    }

    private fun restoreHttpLimitsIfNeeded(epoch: Int) {
        if (epoch <= 0) return
        if (httpLimitsEpoch.get() != epoch) {
            ChangeSourceLog.i(
                "http-limits skip restore epoch=$epoch current=${httpLimitsEpoch.get()}"
            )
            return
        }
        restoreDefaultHttpLimits()
        ChangeSourceLog.i("http-limits restored defaults epoch=$epoch")
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

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun initSearchPool() {
        // Reuse while threadCount unchanged. Prefer IO.limitedParallelism over
        // FixedThreadPool(N): creating dozens/hundreds of OS threads at dialog open
        // was a main cause of first-open jank when 「更新和搜索线程数」is high (e.g. 100).
        val n = threadCount()
        if (searchPool != null && searchPoolSize == n) return
        (searchPool as? Closeable)?.close()
        searchPoolSize = n
        searchPool = Dispatchers.IO.limitedParallelism(n)
    }

    /**
     * Deep toc/content concurrency — capped so hits do not monopolize ask slots.
     * Session evidence 2026-08-05: mapParallel(100) held through word-count → done stuck.
     */
    private fun deepParallel(): Int =
        min(threadCount(), DEEP_PARALLEL_CAP).coerceAtLeast(1)

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun initDeepPool() {
        val n = deepParallel()
        if (deepPool != null && deepPoolSize == n && deepGate != null) return
        (deepPool as? Closeable)?.close()
        deepPoolSize = n
        deepPool = Dispatchers.IO.limitedParallelism(n)
        deepGate = Semaphore(n)
    }

    companion object {
        /** Max concurrent toc/content probes after ask releases the slot. */
        const val DEEP_PARALLEL_CAP = 16
    }

    /** Gate flags from 换源 menu — used for display filter + deep-probe eligibility. */
    private fun decorateChangeSourceHit(book: SearchBook) {
        ChangeBookSourceQuality.decorateSearchHitForChangeSource(book)
    }

    private fun isAcceptableChangeSourceHit(book: SearchBook, requireAuthor: Boolean): Boolean =
        ChangeBookSourceQuality.isAcceptableChangeSourceHit(
            book,
            author,
            requireAuthor = requireAuthor,
            filterNonNovelHost = AppConfig.changeSourceFilterNonNovelHost,
            filterNonBookIntro = AppConfig.changeSourceFilterNonBookIntro,
        )

    open fun refresh(): Boolean {
        getDbSearchBooks().let { rows ->
            searchBooks.clear()
            rows.forEach { decorateChangeSourceHit(it) }
            searchBooks.addAll(rows)
            searchCallback?.upAdapter()
        }
        return synchronized(searchBooks) { searchBooks.isEmpty() }.also { isEmpty ->
            if (!isEmpty && AppConfig.changeSourceLoadWordCount) {
                refreshResultMeasurements()
            }
        }
    }

    /**
     * 搜索书籍
     */
    open fun startSearch() {
        val operation = operationState.reserveOperation()
        execute {
            operationPreparation.withLock {
                if (!operationState.runIfCurrent(operation, ::stopCurrentTask)) {
                    return@withLock
                }
                referenceWordCount = getCachedReferenceWordCount()
                relativeFilterWarningShown = false
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
                wordCountEvalByLocalIndex.clear()
                probeContentSamples.clear()
                contentRefSimByOrigin.clear()
                qualityTiers.clear()
                sessionSoftFail.clear()
                probingNames.clear()
                deepInFlightNames.clear()
                deepJobs.clear()
                completedProbeCount.set(0)
                qualityOkCount.set(0)
                earlyStopped.set(false)
                searchHitCount.set(0)
                listPublishCount.set(0)
                missEmptyCount.set(0)
                missTimeoutCount.set(0)
                missErrorCount.set(0)
                missContentBadCount.set(0)
                lastProgressLogCompleted.set(-1)
                lastProgressPublishMs.set(0L)
                _changeSourceProgress.value = ChangeSourceProgressUi()
                // Disk title-empty (TTL) → memory before ask-order / skips.
                runCatching { ChangeSourceTitleEmptyPrefs.hydrate(name, author) }
                askHostBucket = CheckHostTokenBucket(
                    maxTokensPerHost = CheckHostTokenBucket.DEFAULT_MAX_TOKENS,
                    refillPerSecond = CheckHostTokenBucket.DEFAULT_REFILL_PER_SECOND,
                )
                val t0 = System.currentTimeMillis()
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
                val tLoad = System.currentTimeMillis()
                SourceHelp.ensureRespondTimeHealed()
                val tHeal = System.currentTimeMillis()
                val threads = threadCount()
                val typed = oldBook?.let {
                    BookSourceTypeMapper.filterSameType(loaded, it.type)
                } ?: loaded
                bookSourceParts.addAll(
                    AskSourceOrder.order(
                        typed,
                        threadCount = threads,
                        demoteUrls = ChangeSourceAskMemory.snapshot(),
                    )
                )
                val tOrder = System.currentTimeMillis()
                if (!AppConfig.changeSourceEarlyStop) {
                    ChangeSourceLog.i("early-stop pref off; will ask all ${bookSourceParts.size} sources")
                }
                operationState.startTaskIfCurrent(operation) {
                    initSearchPool()
                    initDeepPool()
                    val httpEpoch = raiseHttpLimitsForSearch()
                    val tPool = System.currentTimeMillis()
                    val headSample = bookSourceParts.take(5).joinToString(" | ") {
                        "${it.bookSourceName}(${it.respondTime})"
                    }
                    ChangeSourceLog.i(
                        "start book=$name author=$author sources=${bookSourceParts.size} " +
                            "threads=$threads deepParallel=${deepParallel()} " +
                            "demoted=${ChangeSourceAskMemory.snapshot().size} " +
                            "loadInfo=${AppConfig.changeSourceLoadInfo} " +
                            "loadToc=${AppConfig.changeSourceLoadToc} " +
                            "loadWordCount=${AppConfig.changeSourceLoadWordCount} " +
                            "earlyStop=${AppConfig.changeSourceEarlyStop} " +
                            "earlyStopTarget=${AppConfig.changeSourceEarlyStopCount} " +
                            "group=${searchGroup.ifBlank { "(all)" }} " +
                            "timing load=${tLoad - t0}ms heal=${tHeal - tLoad}ms " +
                            "order=${tOrder - tHeal}ms pool=${tPool - tOrder}ms total=${tPool - t0}ms " +
                            "askHead=[$headSample]"
                    )
                    search(httpLimitsEpoch = httpEpoch, operation = operation)
                }
            }
        }.invokeOnCompletion { finishPreparingOperation(operation) }
    }

    fun startSearch(origin: String) {
        val operation = operationState.reserveOperation()
        execute {
            operationPreparation.withLock {
                if (!operationState.runIfCurrent(operation, ::stopCurrentTask)) {
                    return@withLock
                }
                bookSourceParts.clear()
                tocMap.clear()
                bookMap.clear()
                tocMapChapterCount = 0
                wordCountEvalContext = null
                wordCountEvalByLocalIndex.clear()
                probeContentSamples.clear()
                contentRefSimByOrigin.clear()
                qualityTiers.clear()
                sessionSoftFail.clear()
                probingNames.clear()
                deepInFlightNames.clear()
                deepJobs.clear()
                completedProbeCount.set(0)
                qualityOkCount.set(0)
                earlyStopped.set(false)
                _changeSourceProgress.value = ChangeSourceProgressUi()
                askHostBucket = CheckHostTokenBucket(
                    maxTokensPerHost = CheckHostTokenBucket.DEFAULT_MAX_TOKENS,
                    refillPerSecond = CheckHostTokenBucket.DEFAULT_REFILL_PER_SECOND,
                )
                bookSourceParts.add(appDb.bookSourceDao.getBookSourcePart(origin)!!)
                searchBooks.removeIf { it.origin == origin }
                // Keep 「命中」aligned with remaining list; new hits add on ask.
                searchHitCount.set(synchronized(searchBooks) { searchBooks.size })
                listPublishCount.set(searchHitCount.get())
                operationState.startTaskIfCurrent(operation) {
                    initSearchPool()
                    initDeepPool()
                    search(
                        httpLimitsEpoch = raiseHttpLimitsForSearch(),
                        operation = operation,
                    )
                }
            }
        }.invokeOnCompletion { finishPreparingOperation(operation) }
    }

    protected fun clearEarlyStopped() {
        earlyStopped.set(false)
    }

    private fun noteAskMiss(bookSourceUrl: String, reason: String, processDemote: Boolean) {
        sessionSoftFail.add(bookSourceUrl)
        // Empty search for this book ≠ dead source — do not poison global ask-order.
        if (processDemote) {
            ChangeSourceAskMemory.noteMiss(bookSourceUrl)
        }
        when (reason) {
            "empty" -> missEmptyCount.incrementAndGet()
            "timeout" -> missTimeoutCount.incrementAndGet()
            "error" -> missErrorCount.incrementAndGet()
            "content-bad" -> missContentBadCount.incrementAndGet()
        }
        ChangeSourceLog.i(
            "miss $reason $bookSourceUrl processDemote=$processDemote " +
                "done=${completedProbeCount.get()} list=${searchBooks.size} " +
                "inFlight=${probingNames.size}"
        )
    }

    private fun publishProgress(
        completed: Int = completedProbeCount.get(),
        early: Boolean = earlyStopped.get(),
        finished: Boolean = false,
        force: Boolean = false,
    ) {
        // High threadCount floods StateFlow → Main subtitle/bar jank; coalesce ~12.5fps.
        // early-stop wind-down still throttles; only finished / explicit force skips.
        val nowMs = System.currentTimeMillis()
        if (!force && !finished) {
            val prev = lastProgressPublishMs.get()
            if (nowMs - prev < 80L) return
            if (!lastProgressPublishMs.compareAndSet(prev, nowMs)) return
        } else {
            lastProgressPublishMs.set(nowMs)
        }
        val inFlightUrls = probingNames.toList()
        val inFlight = inFlightUrls.size
        val deepInFlight = deepInFlightNames.size
        // Show several in-flight names so parallel work is visible (not just one serial name).
        val sample = inFlightUrls.take(3).joinToString("、") { url ->
            bookSourceParts.find { it.bookSourceUrl == url }?.bookSourceName ?: url
        }
        val more = (inFlight - 3).coerceAtLeast(0)
        val askLabel = when {
            inFlight == 0 -> ""
            more > 0 -> "询问中 $sample 等${more}个"
            else -> "询问中 $sample"
        }
        val deepLabel = if (deepInFlight > 0) "深探$deepInFlight/${deepParallel()}" else ""
        val okHint = if (!early && AppConfig.changeSourceEarlyStop) {
            "好源${qualityOkCount.get()}/${AppConfig.changeSourceEarlyStopCount}"
        } else {
            ""
        }
        val probingLabel = listOf(okHint, askLabel, deepLabel)
            .filter { it.isNotEmpty() }
            .joinToString(" · ")
        val label = when {
            early -> "" // freeze subtitle during early-stop wind-down (no probing flicker)
            else -> probingLabel
        }
        _changeSourceProgress.value = ChangeSourceProgressUi(
            completed = completed,
            inFlight = inFlight,
            concurrency = threadCount(),
            deepInFlight = deepInFlight,
            label = label,
            qualityOk = qualityOkCount.get(),
            hitCount = searchHitCount.get(),
            earlyStopped = early,
            finished = finished,
        )
        // Milestone progress for analysis (avoid per-probe AppLog flood).
        if (finished || early || completed == 0 ||
            completed - lastProgressLogCompleted.get() >= 10
        ) {
            lastProgressLogCompleted.set(completed)
            ChangeSourceLog.i(
                "progress done=$completed/${bookSourceParts.size} " +
                    "inFlight=$inFlight/${threadCount()} deep=$deepInFlight/${deepParallel()} " +
                    "list=${searchBooks.size} qualityOk=${qualityOkCount.get()} " +
                    "hits=${searchHitCount.get()} published=${listPublishCount.get()} " +
                    "early=$early finished=$finished label=${label.take(80)}"
            )
        }
    }

    private fun search(httpLimitsEpoch: Int, operation: Long) {
        val parts = bookSourceParts.toList()
        val parallelism = threadCount()
        task = viewModelScope.launch(searchPool!!) {
            supervisorScope {
                AskSourcePrefetch.emitSources(parts).onStart {
                    searchStateData.postValue(true)
                    publishProgress(force = true)
                }.mapParallel(parallelism) { source ->
                    if (earlyStopped.get()) return@mapParallel source
                    if (source.bookSourceUrl in sessionSoftFail) return@mapParallel source
                    probingNames.add(source.bookSourceUrl)
                    publishProgress()
                    try {
                        if (ChangeSourceAskMemory.isTitleEmpty(name, author, source.bookSourceUrl)) {
                            ChangeSourceLog.i(
                                "miss empty-cached ${source.bookSourceUrl} " +
                                    "done=${completedProbeCount.get()} list=${searchBooks.size} " +
                                    "inFlight=${probingNames.size}"
                            )
                            return@mapParallel source
                        }
                        // Ask only — deep toc/word scheduled separately so empties keep draining.
                        searchAsk(source)
                    } catch (e: TimeoutCancellationException) {
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
                        if (earlyStopped.get()) {
                            publishProgress(early = true)
                        } else {
                            publishProgress()
                        }
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
                            deepJobs.forEach { it.cancel() }
                            publishProgress(early = true, force = true)
                            currentCoroutineContext().cancel()
                        }
                    }
                }.onCompletion { cause ->
                    withContext(NonCancellable + IO) {
                        try {
                            // Ask wave done — wait remaining deep probes (unless cancelled/early-stop).
                            if (cause == null || earlyStopped.get()) {
                                val pending = deepJobs.toList()
                                if (pending.isNotEmpty()) {
                                    ChangeSourceLog.i(
                                        "deep-wait jobs=${pending.size} deepInFlight=${deepInFlightNames.size}"
                                    )
                                    pending.joinAll()
                                }
                            } else {
                                deepJobs.forEach { it.cancel() }
                            }
                            applyBookQualityGates(force = true)
                            if (earlyStopped.get()) {
                                dropPendingWordCountRows()
                            }
                            RespondTimeUpdater.flush()
                            probingNames.clear()
                            deepInFlightNames.clear()
                            publishProgress(
                                early = earlyStopped.get(),
                                finished = true,
                                force = true,
                            )
                            val sorted = runCatching { sortSearchBooks(searchBooks.toList()) }
                                .getOrDefault(searchBooks.toList())
                            val top = sorted.take(8).joinToString(" | ") { b ->
                                val tier = ChangeBookSourceQuality.contentSortTier(
                                    b.chapterWordCount,
                                    b.chapterWordCountText,
                                    b.origin in sessionSoftFail,
                                    b.qualityVerdict,
                                )
                                "${b.originName}(w=${b.chapterWordCount},v=${b.qualityVerdict}," +
                                    "s=${b.smartScore},t=$tier,ms=${b.respondTime})"
                            }
                            ChangeSourceLog.i(
                                "finish cause=${cause?.javaClass?.simpleName ?: "ok"} " +
                                    "early=${earlyStopped.get()} " +
                                    "completed=${completedProbeCount.get()}/${bookSourceParts.size} " +
                                    "list=${searchBooks.size} qualityOk=${qualityOkCount.get()} " +
                                    "hits=${searchHitCount.get()} published=${listPublishCount.get()} " +
                                    "missEmpty=${missEmptyCount.get()} missTimeout=${missTimeoutCount.get()} " +
                                    "missError=${missErrorCount.get()} missContentBad=${missContentBadCount.get()} " +
                                    "top=[$top]"
                            )
                            searchStateData.postValue(false)
                            warnIfRelativeReferenceUnavailable()
                            if (cause == null || earlyStopped.get()) {
                                onSearchTaskFinished(searchBooks.isEmpty())
                            }
                        } finally {
                            restoreHttpLimitsIfNeeded(httpLimitsEpoch)
                        }
                    }
                }.catch {
                    ChangeSourceLog.w("搜索出错 ${it.localizedMessage}", it)
                    AppLog.put("换源搜索出错\n${it.localizedMessage}", it)
                }.collect()
            }
        }.also { task ->
            task.invokeOnCompletion { refreshPendingMeasurements(operation) }
        }
    }

    /**
     * Search-only ask. On hit: early [list+] as pending, then schedule deep on [deepPool]
     * without holding the ask [mapParallel] slot.
     */
    private suspend fun searchAsk(source: BookSource) {
        val checkAuthor = AppConfig.changeSourceCheckAuthor
        val loadInfo = AppConfig.changeSourceLoadInfo
        val loadToc = AppConfig.changeSourceLoadToc
        val loadWordCount = AppConfig.changeSourceLoadWordCount
        val host = CheckAlgoRuntime.hostOf(source.bookSourceUrl)
        askHostBucket?.acquire(host, source.concurrentRate)
        val budgetMs = AskTimeoutBudget.forChangeSourceAsk(
            respondTime = source.respondTime,
            sessionDemoted = ChangeSourceAskMemory.isDemoted(source.bookSourceUrl),
        )
        if (budgetMs < AskTimeout.CHANGE_SOURCE_MS) {
            ChangeSourceLog.i(
                "ask-budget ms=$budgetMs rank=${AskTimeoutBudget.rankName(source.respondTime)} " +
                    "demoted=${ChangeSourceAskMemory.isDemoted(source.bookSourceUrl)} " +
                    "origin=${source.bookSourceUrl}"
            )
        }
        val startTime = System.currentTimeMillis()
        val ok = withTimeoutOrNull(budgetMs) {
            // Name-only at ask time; author / host / intro are display filters so
            // toggling them never requires a full re-search of this session's hits.
            val rawBooks = WebBook.searchBookAwait(
                source, name,
                filter = { fName, _, _ -> fName == name },
            )
            currentCoroutineContext().ensureActive()
            val searchElapsed = System.currentTimeMillis() - startTime
            if (rawBooks.isEmpty()) {
                noteAskMiss(source.bookSourceUrl, "empty", processDemote = false)
                ChangeSourceAskMemory.noteTitleEmpty(name, author, source.bookSourceUrl)
                runCatching { ChangeSourceTitleEmptyPrefs.persistCurrent(name, author) }
                return@withTimeoutOrNull true
            }
            // Persist every title hit; deep-probe only rows that pass current display filters.
            val deepCandidates = rawBooks.filter {
                isAcceptableChangeSourceHit(it, requireAuthor = checkAuthor)
            }
            if (deepCandidates.isEmpty() && rawBooks.isNotEmpty()) {
                ChangeSourceLog.i(
                    "hit display-filtered origin=${source.bookSourceUrl} " +
                        "raw=${rawBooks.size} deep=0 list=${searchBooks.size}"
                )
            }
            searchHitCount.addAndGet(rawBooks.size)
            RespondTimeUpdater.noteSuccessAndMaybeFlush(
                source.bookSourceUrl,
                searchElapsed,
                source.respondTime,
            )
            val deep = loadInfo || loadToc || loadWordCount
            ChangeSourceLog.i(
                "hit origin=${source.bookSourceUrl} name=${source.bookSourceName} " +
                    "searchMs=$searchElapsed results=${rawBooks.size} deepEligible=${deepCandidates.size} " +
                    "deep=$deep list=${searchBooks.size} inFlight=${probingNames.size}"
            )
            if (!deep) {
                if (earlyStopped.get()) return@withTimeoutOrNull true
                rawBooks.forEach { searchBook ->
                    searchBook.respondTime = searchElapsed.toInt()
                    publishSearchBook(searchBook)
                }
                return@withTimeoutOrNull true
            }
            ChangeSourceLog.i(
                "deep-schedule origin=${source.bookSourceUrl} " +
                    "loadInfo=$loadInfo loadToc=$loadToc loadWordCount=$loadWordCount " +
                    "deepN=${deepCandidates.size}"
            )
            if (earlyStopped.get()) return@withTimeoutOrNull true
            // Early-list deep candidates as pending. Display-filtered title hits stay
            // listed without pending so early-stop does not delete them.
            val pendingLabel =
                getApplication<Application>().getString(R.string.change_source_pending_word)
            val deepUrls = deepCandidates.map { it.bookUrl }.toHashSet()
            rawBooks.forEach { searchBook ->
                searchBook.respondTime = searchElapsed.toInt()
                if (searchBook.bookUrl in deepUrls) {
                    searchBook.chapterWordCount = 0
                    searchBook.chapterWordCountText = pendingLabel
                    searchBook.qualityVerdict = ChangeBookSourceQuality.QualityVerdict.Pending
                    searchBook.qualityTags = emptyList()
                    searchBook.smartScore = -1
                }
                publishSearchBook(searchBook)
            }
            if (deepCandidates.isEmpty()) return@withTimeoutOrNull true
            if (earlyStopped.get()) return@withTimeoutOrNull true
            val pool = deepPool ?: IO
            val gate = deepGate
            // Register before dispatch so early-stop cancel cannot miss this job.
            val deepJob = Job()
            deepJobs.add(deepJob)
            viewModelScope.launch(pool + deepJob) {
                var held = false
                try {
                    val tGate = System.currentTimeMillis()
                    gate?.acquire()
                    held = gate != null
                    val gateWaitMs = System.currentTimeMillis() - tGate
                    if (gateWaitMs >= 100) {
                        ChangeSourceLog.i(
                            "deep-gate-wait ms=$gateWaitMs origin=${source.bookSourceUrl}"
                        )
                    }
                    if (earlyStopped.get()) return@launch
                    deepInFlightNames.add(source.bookSourceUrl)
                    publishProgress()
                    deepCandidates.forEach { searchBook ->
                        currentCoroutineContext().ensureActive()
                        if (earlyStopped.get()) return@launch
                        try {
                            loadBookInfo(source, searchBook.toBook())
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            ChangeSourceLog.i(
                                "deep-error origin=${source.bookSourceUrl} " +
                                    "url=${searchBook.bookUrl.take(80)} ${e.localizedMessage}"
                            )
                            dropSearchBookHit(searchBook, "deep-error")
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ChangeSourceLog.i(
                        "deep-error origin=${source.bookSourceUrl} ${e.localizedMessage}"
                    )
                    noteAskMiss(source.bookSourceUrl, "error", processDemote = true)
                } finally {
                    deepInFlightNames.remove(source.bookSourceUrl)
                    if (held) {
                        gate?.release()
                    }
                    deepJobs.remove(deepJob)
                    deepJob.complete()
                    publishProgress()
                }
            }
            true
        }
        if (ok != true) {
            noteAskMiss(source.bookSourceUrl, "timeout", processDemote = true)
        }
    }

    private suspend fun loadBookInfo(source: BookSource, book: Book) {
        val t0 = System.currentTimeMillis()
        if (book.tocUrl.isEmpty()) {
            WebBook.getBookInfoAwait(source, book)
            ChangeSourceLog.i(
                "phase info origin=${source.bookSourceUrl} ms=${System.currentTimeMillis() - t0} " +
                    "tocUrlEmpty=${book.tocUrl.isEmpty()} latest=${book.latestChapterTitle?.take(24) ?: ""}"
            )
        }
        if (AppConfig.changeSourceLoadToc || AppConfig.changeSourceLoadWordCount) {
            loadBookToc(source, book)
        } else {
            //从详情页里获取最新章节
            publishSearchBook(book.toSearchBook())
        }
    }

    private suspend fun loadBookToc(source: BookSource, book: Book) {
        val t0 = System.currentTimeMillis()
        val chapters = WebBook.getChapterListAwait(source, book).getOrThrow()
        for (chapter in chapters) {
            chapter.internString()
        }
        putToc(book, chapters)
        bookMap[book.primaryStr()] = book
        book.releaseHtmlData()
        ChangeSourceLog.i(
            "phase toc origin=${source.bookSourceUrl} chapters=${chapters.size} " +
                "ms=${System.currentTimeMillis() - t0}"
        )
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
            val hit = book.toSearchBook()
            dropSearchBookHit(hit, "empty-toc")
            return@coroutineScope
        }
        val chapterIndex = wordCountChapterIndex(chapters).coerceIn(0, chapters.lastIndex)
        val bookChapter = chapters[chapterIndex]
        var title = bookChapter.title.trim()
        if (title.length > 20) {
            title = title.substring(0, 20) + "…"
        }
        val startTime = System.currentTimeMillis()
        var contentMs = -1L
        var queueMs = 0L
        var evalMs = -1L
        var processedContent: String? = null
        val pair = try {
            val nextChapterUrl = chapters.getOrNull(chapterIndex + 1)?.url
            val tContent0 = System.currentTimeMillis()
            // contentMs = network+processor wall (may include OkHttp queue);
            // queueMs stripped for UI respondTime via HttpCallTiming.
            val (contentRaw, httpTiming) = HttpCallTiming.measure {
                var content = WebBook.getContentAwait(
                    source, book, bookChapter, nextChapterUrl, false
                )
                contentProcessor.getContent(oldBook!!, bookChapter, content, false).toString()
            }
            contentMs = System.currentTimeMillis() - tContent0
            queueMs = httpTiming.queueMs
            if (httpTiming.callCount == 0L && contentMs >= 200) {
                ChangeSourceLog.i(
                    "http-timing-miss origin=${source.bookSourceUrl} contentMs=$contentMs " +
                        "(no tagged calls — queue not subtracted)"
                )
            }
            processedContent = contentRaw
            val tEval0 = System.currentTimeMillis()
            val evalCtx = bookChangeContentEvalContext(chapterIndex, bookChapter.title)
            val diag = ChangeChapterVerify.evaluateContentDiag(contentRaw, evalCtx)
            evalMs = System.currentTimeMillis() - tEval0
            diag.refSim?.let { contentRefSimByOrigin[book.bookUrl] = it }
            ChangeSourceLog.i(
                "phase word-eval origin=${source.bookSourceUrl} reason=${diag.reason} " +
                    "len=${diag.contentLen} stitch=${diag.stitch} " +
                    "refSim=${diag.refSim?.let { "%.4f".format(it) } ?: "-"} " +
                    "expected=${diag.expectedChars ?: "-"} " +
                    "contentMs=$contentMs queueMs=$queueMs evalMs=$evalMs " +
                    "chapter=[$chapterIndex] ${title.take(24)}"
            )
            val measured = diag.contentLen.coerceAtLeast(0)
            val verdict = ChangeBookSourceQuality.verdictFromContentQuality(diag.quality, measured)
            val tag = when (diag.quality) {
                is ChangeChapterVerify.ContentQuality.Ok -> null
                ChangeChapterVerify.ContentQuality.TooShort ->
                    getApplication<Application>().getString(R.string.change_source_chapter_too_short)
                ChangeChapterVerify.ContentQuality.AntiTheft ->
                    getApplication<Application>().getString(R.string.change_source_chapter_anti_theft)
                ChangeChapterVerify.ContentQuality.Hijack ->
                    getApplication<Application>().getString(R.string.change_source_chapter_hijack)
            }
            Triple(measured, verdict, tag)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            ChangeSourceLog.i(
                "phase word-eval origin=${source.bookSourceUrl} reason=fetch_error " +
                    "contentMs=$contentMs queueMs=$queueMs evalMs=$evalMs " +
                    "err=${t.javaClass.simpleName}:${t.localizedMessage?.take(80)}"
            )
            Triple(
                -1,
                ChangeBookSourceQuality.QualityVerdict.FetchError,
                getApplication<Application>().getString(
                    R.string.change_source_chapter_content_fail,
                ) + "：${t.localizedMessage?.take(40) ?: ""}",
            )
        }
        val endTime = System.currentTimeMillis()
        // UI respondTime = source fetch work only (exclude OkHttp dispatcher queue + local eval).
        val workMs = when {
            contentMs >= 0 -> (contentMs - queueMs).coerceAtLeast(0L)
            else -> (endTime - startTime).coerceAtLeast(0L)
        }
        val (measuredChars, verdict, qualityTag) = pair
        val searchBook = book.toSearchBook().apply {
            chapterWordCount = measuredChars
            respondTime = workMs.toInt()
            qualityVerdict = verdict
            qualityTags = listOfNotNull(qualityTag?.takeIf { it.isNotBlank() })
            chapterWordCountText = when {
                measuredChars >= 0 ->
                    ChangeBookSourceQuality.metricLine(measuredChars, respondTime)
                else ->
                    getApplication<Application>().getString(R.string.change_source_chapter_content_fail)
            }
        }
        val tier = ChangeBookSourceQuality.contentSortTier(
            chapterWordCount = measuredChars,
            wordCountText = searchBook.chapterWordCountText,
            softFailed = false,
            verdict = verdict,
        )
        ChangeSourceLog.i(
            "phase word origin=${source.bookSourceUrl} chars=$measuredChars " +
                "verdict=$verdict tier=$tier ms=${endTime - startTime} contentMs=$contentMs " +
                "queueMs=$queueMs workMs=$workMs evalMs=$evalMs " +
                "ok=${verdict == ChangeBookSourceQuality.QualityVerdict.Ok} " +
                "list=${searchBooks.size}"
        )
        val contentBad = ChangeBookSourceQuality.isContentBadVerdict(verdict)
        if (contentBad) {
            mergeTier(searchBook.bookUrl, ChangeBookSourceQuality.TIER_CONTENT_BAD)
            missContentBadCount.incrementAndGet()
            publishSearchBook(searchBook, tocSize = chapters.size)
            applyBookQualityGates(force = false)
        } else {
            if (verdict == ChangeBookSourceQuality.QualityVerdict.Ok && processedContent != null) {
                probeContentSamples[searchBook.bookUrl] = processedContent
                qualityOkCount.incrementAndGet()
            }
            publishSearchBook(searchBook, tocSize = chapters.size)
            applyBookQualityGates(force = false)
        }
    }

    /** Remove a pending/failed origin from the on-screen list + DB row. */
    private fun dropSearchBookOrigin(origin: String, reason: String) {
        val (removed, wasOk) = synchronized(searchBooks) {
            val doomed = searchBooks.filter { it.origin == origin }
            if (doomed.isEmpty()) return@synchronized Pair(emptyList(), false)
            val ok = doomed.any {
                it.qualityVerdict == ChangeBookSourceQuality.QualityVerdict.Ok
            }
            searchBooks.removeAll { it.origin == origin }
            Pair(doomed, ok)
        }
        if (removed.isEmpty()) return
        if (wasOk) {
            qualityOkCount.updateAndGet { (it - 1).coerceAtLeast(0) }
            probeContentSamples.remove(origin)
        }
        runCatching { appDb.searchBookDao.delete(*removed.toTypedArray()) }
        searchCallback?.upAdapter()
        ChangeSourceLog.i(
            "list- drop origin=$origin reason=$reason wasOk=$wasOk size=${searchBooks.size}"
        )
    }

    /** Remove one search hit (by bookUrl) so aggregator siblings stay listed. */
    private fun dropSearchBookHit(searchBook: SearchBook, reason: String) {
        val bookUrl = searchBook.bookUrl
        val (removed, wasOk) = synchronized(searchBooks) {
            val doomed = searchBooks.filter { it.bookUrl == bookUrl }
            if (doomed.isEmpty()) return@synchronized Pair(emptyList(), false)
            val ok = doomed.any {
                it.qualityVerdict == ChangeBookSourceQuality.QualityVerdict.Ok
            }
            searchBooks.removeAll { it.bookUrl == bookUrl }
            Pair(doomed, ok)
        }
        if (removed.isEmpty()) return
        if (reason == "content-bad" || reason.startsWith("consensus:")) {
            missContentBadCount.incrementAndGet()
        }
        if (wasOk) {
            qualityOkCount.updateAndGet { (it - 1).coerceAtLeast(0) }
        }
        probeContentSamples.remove(bookUrl)
        contentRefSimByOrigin.remove(bookUrl)
        qualityTiers.remove(bookUrl)
        runCatching { appDb.searchBookDao.delete(*removed.toTypedArray()) }
        searchCallback?.upAdapter()
        ChangeSourceLog.i(
            "list- drop hit origin=${searchBook.origin} reason=$reason wasOk=$wasOk " +
                "url=${bookUrl.take(80)} size=${searchBooks.size}"
        )
    }

    private fun publishSearchBook(searchBook: SearchBook, tocSize: Int? = null) {
        annotateMetaQuality(searchBook, tocSize)
        searchCallback?.searchSuccess(searchBook)
    }

    private fun annotateMetaQuality(searchBook: SearchBook, tocSize: Int?) {
        val hitKey = searchBook.bookUrl
        val local = oldBook
        val referenceTrusted = wordCountEvalContext?.referenceTrusted != false
        val tags = searchBook.qualityTags.toMutableList()
        var tocMismatch = false
        val latestMatch = ChangeBookSourceQuality.latestMatchesLocal(
            local?.latestChapterTitle,
            searchBook.latestChapterTitle,
        )
        if (tocSize != null &&
            local != null &&
            !ChangeBookSourceQuality.tocConsistent(local.totalChapterNum, tocSize)
        ) {
            if (ChangeBookSourceQuality.shouldShowTocMismatchBadge(
                    searchBook.chapterWordCount,
                    contentRefSimByOrigin[hitKey],
                    referenceTrusted = referenceTrusted,
                    verdict = searchBook.qualityVerdict,
                )
            ) {
                mergeTier(hitKey, ChangeBookSourceQuality.TIER_TOC_BAD)
                tocMismatch = true
                val label = getApplication<Application>().getString(R.string.change_source_toc_mismatch)
                if (label !in tags) tags.add(label)
            }
        }
        // Hard tip mismatch always surfaces as a tag + score penalty, even when soft-meta
        // badge gates would hide "tip lag" on quality-OK + trusted local ref.
        if (latestMatch == false) {
            mergeTier(hitKey, ChangeBookSourceQuality.TIER_LATEST_BAD)
            val label = getApplication<Application>()
                .getString(R.string.change_source_latest_mismatch)
            if (label !in tags) tags.add(label)
        }
        searchBook.qualityTags = tags
        refreshSmartScore(
            searchBook,
            latestMatch = latestMatch,
            tocMismatch = tocMismatch,
        )
    }

    protected fun refreshSmartScore(
        searchBook: SearchBook,
        latestMatch: Boolean? = null,
        tocMismatch: Boolean? = null,
    ) {
        val tipMatch = latestMatch ?: ChangeBookSourceQuality.latestMatchesLocal(
            oldBook?.latestChapterTitle,
            searchBook.latestChapterTitle,
        )
        val toc = tocMismatch ?: searchBook.qualityTags.any {
            it.contains("目录") || it.contains("TOC", ignoreCase = true)
        }
        searchBook.smartScore = ChangeBookSourceQuality.smartScore(
            measuredChars = searchBook.chapterWordCount,
            verdict = searchBook.qualityVerdict,
            contentRefSim = contentRefSimByOrigin[searchBook.bookUrl],
            latestMatch = tipMatch,
            tocMismatch = toc,
            respondTimeMs = searchBook.respondTime,
            userScore = getBookScore(searchBook),
            expectedChars = wordCountEvalContext?.expectedChars,
        )
    }

    /** Drop unfinished deep-probe pending rows after early-stop cancels deep jobs. */
    private fun dropPendingWordCountRows() {
        val pendingLabel =
            getApplication<Application>().getString(R.string.change_source_pending_word)
        val removed = synchronized(searchBooks) {
            val doomed = searchBooks.filter {
                it.chapterWordCount == 0 && it.chapterWordCountText == pendingLabel
            }
            if (doomed.isEmpty()) return@synchronized emptyList()
            searchBooks.removeAll {
                it.chapterWordCount == 0 && it.chapterWordCountText == pendingLabel
            }
            doomed
        }
        if (removed.isEmpty()) return
        runCatching { appDb.searchBookDao.delete(*removed.toTypedArray()) }
        searchCallback?.upAdapter()
        ChangeSourceLog.i(
            "list- drop pending-cancel count=${removed.size} size=${searchBooks.size}"
        )
    }

    private fun applyBookQualityGates(force: Boolean) {
        val samples = probeContentSamples.toMap()
        if (force || samples.size >= ChangeChapterVerify.MULTI_SOURCE_MIN_SAMPLES) {
            val outliers = ChangeChapterVerify.multiSourceOutlierOrigins(
                samples = samples,
                referenceContent = wordCountEvalContext?.referenceContent,
                referenceTrusted = wordCountEvalContext?.referenceTrusted != false,
            )
            for (bookUrl in outliers) {
                val book = searchBooks.find { it.bookUrl == bookUrl } ?: continue
                demoteSearchHitContent(
                    book,
                    getApplication<Application>().getString(R.string.change_source_chapter_hijack),
                )
            }
        }
        val titles = searchBooks.mapNotNull { book ->
            book.latestChapterTitle?.trim()?.takeIf { it.isNotEmpty() }?.let { book.bookUrl to it }
        }.toMap()
        if (force || titles.size >= ChangeChapterVerify.MULTI_SOURCE_MIN_SAMPLES) {
            val latestOutliers = ChangeBookSourceQuality.latestTitleOutliers(
                titlesByOrigin = titles,
                localLatest = oldBook?.latestChapterTitle,
            )
            for (bookUrl in latestOutliers) {
                val book = searchBooks.find { it.bookUrl == bookUrl } ?: continue
                if (!ChangeBookSourceQuality.shouldShowLatestMismatchBadge(
                        book.chapterWordCount,
                        contentRefSimByOrigin[bookUrl],
                        referenceTrusted = wordCountEvalContext?.referenceTrusted != false,
                        verdict = book.qualityVerdict,
                    )
                ) {
                    continue
                }
                mergeTier(bookUrl, ChangeBookSourceQuality.TIER_LATEST_BAD)
                val label = getApplication<Application>()
                    .getString(R.string.change_source_latest_mismatch)
                if (label !in book.qualityTags) {
                    book.qualityTags = book.qualityTags + label
                }
                refreshSmartScore(book, latestMatch = false)
            }
        }
        if (force || titles.size >= ChangeChapterVerify.MULTI_SOURCE_MIN_SAMPLES ||
            samples.size >= ChangeChapterVerify.MULTI_SOURCE_MIN_SAMPLES
        ) {
            searchCallback?.upAdapter()
        }
    }

    private fun demoteSearchHitContent(searchBook: SearchBook, badge: String) {
        mergeTier(searchBook.bookUrl, ChangeBookSourceQuality.TIER_CONTENT_BAD)
        probeContentSamples.remove(searchBook.bookUrl)
        val wasOk = searchBook.qualityVerdict == ChangeBookSourceQuality.QualityVerdict.Ok
        if (wasOk) {
            qualityOkCount.updateAndGet { (it - 1).coerceAtLeast(0) }
        }
        // Keep measured char count; mark hijack via verdict + quality tag.
        searchBook.qualityVerdict = ChangeBookSourceQuality.QualityVerdict.Hijack
        if (badge !in searchBook.qualityTags) {
            searchBook.qualityTags = searchBook.qualityTags + badge
        }
        if (searchBook.chapterWordCount >= 0) {
            searchBook.chapterWordCountText = ChangeBookSourceQuality.metricLine(
                searchBook.chapterWordCount,
                searchBook.respondTime,
            )
        }
        missContentBadCount.incrementAndGet()
        refreshSmartScore(searchBook)
        runCatching { appDb.searchBookDao.insert(searchBook) }
        synchronized(searchBooks) {
            val idx = searchBooks.indexOfFirst { it.bookUrl == searchBook.bookUrl }
            if (idx >= 0) searchBooks[idx] = searchBook
        }
        searchCallback?.upAdapter()
        ChangeSourceLog.i(
            "list~ demote content-bad origin=${searchBook.origin} badge=$badge " +
                "wasOk=$wasOk url=${searchBook.bookUrl.take(80)} size=${searchBooks.size}"
        )
    }

    /**
     * Apply menu 「正文不合格时移除」 — display-only reshuffle; never deletes rows.
     */
    fun applyDropContentBadPreference() {
        onDisplayFilterPrefsChanged()
    }

    private fun demoteOriginContent(origin: String, badge: String) {
        sessionSoftFail.add(origin)
        mergeTier(origin, ChangeBookSourceQuality.TIER_CONTENT_BAD)
        val hits = synchronized(searchBooks) { searchBooks.filter { it.origin == origin } }
        for (hit in hits) {
            demoteSearchHitContent(hit, badge)
        }
    }

    private fun mergeTier(origin: String, tier: Int) {
        qualityTiers[origin] = ChangeBookSourceQuality.worseTier(
            qualityTiers[origin] ?: ChangeBookSourceQuality.TIER_UNKNOWN,
            tier,
        )
    }

    /**
     * Local chapter body aligned to the candidate chapter being probed.
     * Cached **per aligned local chapter index** (not once for the whole search —
     * a wrong first hit must not lock the reference for every later source).
     * If disk cache is empty, one-shot fetch from the book's current origin.
     */
    private suspend fun bookChangeContentEvalContext(
        candidateChapterIndex: Int,
        candidateTitle: String,
    ): ChangeChapterVerify.ContentEvalContext {
        val book = oldBook ?: return ChangeChapterVerify.ContentEvalContext()
            .also { wordCountEvalContext = it }
        val localChapters = appDb.bookChapterDao.getChapterList(book.bookUrl)
        if (localChapters.isEmpty()) {
            return ChangeChapterVerify.ContentEvalContext().also { wordCountEvalContext = it }
        }
        val idx = if (fromReadBookActivity) {
            // From reading: always compare against the chapter the user is on.
            // Candidate-title align can lock onto a wrong local chapter for wrong-book hits.
            wordCountChapterIndex(localChapters).coerceIn(0, localChapters.lastIndex)
        } else {
            ChangeChapterVerify.alignIndex(
                candidateChapterIndex,
                candidateTitle,
                localChapters,
            ) ?: wordCountChapterIndex(localChapters).coerceIn(0, localChapters.lastIndex)
        }
        wordCountEvalByLocalIndex[idx]?.let { return it }
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
        val siblingLengths = localChapters
            .asSequence()
            .drop(maxOf(0, idx - 2))
            .take(8)
            .mapNotNull { ch ->
                BookHelp.getContent(book, ch)?.trim()?.length?.takeIf { it > 0 }
            }
            .toList()
        val trust = ChangeChapterVerify.assessLocalReferenceTrust(
            localTitle = localChapter.title,
            referenceContent = reference,
            siblingBodyLengths = siblingLengths,
        )
        val expected = if (trust.trusted) {
            reference?.length?.takeIf { it >= ChangeChapterVerify.MIN_CONTENT_CHARS }
        } else {
            null
        }
        val ctx = ChangeChapterVerify.ContentEvalContext(
            expectedChars = expected,
            referenceContent = reference,
            referenceTrusted = trust.trusted,
        )
        wordCountEvalByLocalIndex[idx] = ctx
        val durIdx = wordCountChapterIndex(localChapters).coerceIn(0, localChapters.lastIndex)
        if (wordCountEvalContext == null || idx == durIdx) {
            wordCountEvalContext = ctx
        }
        ChangeSourceLog.i(
            "ref-cache localIdx=$idx title=${localChapter.title.take(24)} " +
                "refLen=${reference?.length ?: 0} expected=${ctx.expectedChars ?: "-"} " +
                "trusted=${trust.trusted} trustReason=${trust.reason}"
        )
        return ctx
    }

    private fun getReferenceWordCount(
        books: List<SearchBook> = synchronized(searchBooks) { searchBooks.toList() },
    ): Int? {
        referenceWordCount?.let { return it }
        val book = oldBook ?: return null
        val measured = books.firstOrNull {
            it.origin == book.origin && it.bookUrl == book.bookUrl
        }?.chapterWordCount?.takeIf { it > 0 }
        if (measured != null) referenceWordCount = measured
        return measured
    }

    private fun getCachedReferenceWordCount(): Int? {
        if (AppConfig.changeSourceWordCountFilterMode !=
            ChangeSourceResultOptions.FILTER_RELATIVE
        ) {
            return null
        }
        val book = oldBook ?: return null
        val chapterIndex = if (fromReadBookActivity) {
            book.durChapterIndex
        } else {
            book.totalChapterNum - 1
        }
        if (chapterIndex < 0) return null
        val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, chapterIndex) ?: return null
        val content = BookHelp.getContent(book, chapter) ?: return null
        return kotlin.runCatching {
            contentProcessor.getContent(book, chapter, content, false).toString().length
        }.getOrNull()?.takeIf { it > 0 }
    }

    private fun warnIfRelativeReferenceUnavailable() {
        if (
            AppConfig.changeSourceWordCountFilterMode ==
            ChangeSourceResultOptions.FILTER_RELATIVE &&
            getReferenceWordCount() == null &&
            !operationState.hasPendingMeasurementRefresh() &&
            !relativeFilterWarningShown
        ) {
            relativeFilterWarningShown = true
            context.toastOnUi(R.string.change_source_relative_word_count_unavailable)
        }
    }

    fun onLoadWordCountChecked() {
        onLoadWordCountChecked(AppConfig.changeSourceLoadWordCount)
    }

    fun onLoadWordCountChecked(isChecked: Boolean) {
        if (isChecked) {
            refreshResultMeasurements()
        } else {
            searchCallback?.upAdapter()
        }
    }

    fun onResultOptionsChanged(reloadMeasurements: Boolean) {
        if (reloadMeasurements) {
            refreshResultMeasurements()
        } else {
            searchCallback?.upAdapter()
        }
    }

    private fun refreshResultMeasurements() {
        val operation = operationState.reserveMeasurementRefresh(
            enabled = AppConfig.changeSourceLoadWordCount,
            hasResults = searchBooks.isNotEmpty(),
        )
        if (operation == null) {
            searchCallback?.upAdapter()
        } else {
            startRefreshList(true, operation)
        }
    }

    private fun refreshPendingMeasurements(operation: Long) {
        operationState.finishTask(operation)?.let {
            startRefreshList(true, it)
        }
    }

    private fun finishPreparingOperation(operation: Long) {
        operationState.finishPreparation(operation)?.let {
            startRefreshList(true, it)
        }
    }

    /**
     * 刷新列表
     */
    fun startRefreshList(onlyRefreshNoWordCountBook: Boolean = false) {
        startRefreshList(onlyRefreshNoWordCountBook, operationState.reserveOperation())
    }

    private fun startRefreshList(
        onlyRefreshNoWordCountBook: Boolean,
        operation: Long,
    ) {
        execute {
            operationPreparation.withLock {
                if (!operationState.runIfCurrent(operation, ::stopCurrentTask)) {
                    return@withLock
                }
                if (onlyRefreshNoWordCountBook && !AppConfig.changeSourceLoadWordCount) {
                    return@withLock
                }
                referenceWordCount = getCachedReferenceWordCount()
                relativeFilterWarningShown = false
                val books = arrayListOf<SearchBook>()
                if (onlyRefreshNoWordCountBook) {
                    searchBooks.filterTo(books) {
                        it.chapterWordCountText == null ||
                            (
                                AppConfig.changeSourceLoadWordCount &&
                                    ChangeBookSourceQuality.needsSessionQualityHydration(
                                        it.qualityVerdict,
                                        it.chapterWordCountText,
                                    )
                                )
                    }
                    searchBooks.removeIf {
                        it.chapterWordCountText == null ||
                            (
                                AppConfig.changeSourceLoadWordCount &&
                                    ChangeBookSourceQuality.needsSessionQualityHydration(
                                        it.qualityVerdict,
                                        it.chapterWordCountText,
                                    )
                                )
                    }
                } else {
                    books.addAll(searchBooks)
                    searchBooks.clear()
                }
                searchCallback?.upAdapter()
                if (books.isEmpty()) {
                    operationState.runIfCurrent(operation) {
                        warnIfRelativeReferenceUnavailable()
                    }
                    return@withLock
                }
                operationState.startTaskIfCurrent(operation) {
                    initSearchPool()
                    refreshList(books, operation)
                }
            }
        }.invokeOnCompletion { finishPreparingOperation(operation) }
    }

    private fun refreshList(books: List<SearchBook>, operation: Long) {
        val httpLimitsEpoch = raiseHttpLimitsForSearch()
        task = viewModelScope.launch(searchPool!!) {
            flow {
                for (searchBook in books) {
                    emit(searchBook)
                }
            }.onStart {
                searchStateData.postValue(true)
            }.mapParallelSafe(threadCount()) {
                val source = appDb.bookSourceDao.getBookSource(it.origin)!!
                // Align with search(): return promptly even if nested Cronet/WebView cleanup lags.
                val ok = withTimeoutOrNull(AskTimeout.CHANGE_SOURCE_MS) {
                    loadBookInfo(source, it.toBook())
                    true
                }
                if (ok != true) {
                    noteAskMiss(it.origin, "timeout", processDemote = true)
                }
            }.onCompletion {
                try {
                    searchStateData.postValue(false)
                    warnIfRelativeReferenceUnavailable()
                } finally {
                    restoreHttpLimitsIfNeeded(httpLimitsEpoch)
                }
            }.catch {
                AppLog.put("换源刷新列表出错\n${it.localizedMessage}", it)
            }.collect()
        }.also { task ->
            task.invokeOnCompletion { refreshPendingMeasurements(operation) }
        }
    }

    /**
     * Load cached hits by book name only. Author / screenKey / host / intro are
     * applied in [passesDisplayFilters] so menu toggles reshuffle without re-ask.
     */
    private fun getDbSearchBooks(): List<SearchBook> {
        return appDb.searchBookDao.changeSourceByGroup(name, "", AppConfig.searchGroup)
    }

    /**
     * 筛选 — display-only; keeps in-memory hits and reshuffles via currentResults().
     */
    fun screen(key: String?) {
        screenKey = key?.trim() ?: ""
        onDisplayFilterPrefsChanged()
    }

    fun startOrStopSearch() {
        if (operationState.isRunning()) {
            stopSearch()
        } else {
            startSearch()
        }
    }

    fun stopSearch() {
        operationState.cancel(::stopCurrentTask)
    }

    private fun stopCurrentTask() {
        val wasActive = task?.isActive == true || deepJobs.any { it.isActive }
        task?.cancel()
        deepJobs.forEach { it.cancel() }
        deepJobs.clear()
        deepInFlightNames.clear()
        // Do not close searchPool here: onCompletion may still flush on it;
        // Pool is reused while threadCount() is unchanged; resized in initSearchPool().
        // onCleared() attempts Closeable.close() (Executor pools); limitedParallelism is a no-op.
        // Http limits: restored in search onCompletion / onCleared (after deep-wait), not here.
        if (wasActive) {
            ChangeSourceLog.i(
                "stop completed=${completedProbeCount.get()}/${bookSourceParts.size} " +
                    "list=${searchBooks.size} qualityOk=${qualityOkCount.get()} " +
                    "hits=${searchHitCount.get()} published=${listPublishCount.get()}"
            )
        }
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
            currentResults().forEach {
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
            synchronized(searchBooks) {
                searchBooks.filter { it.bookUrl == searchBook.bookUrl || it.origin == searchBook.origin }
                    .forEach { refreshSmartScore(it) }
            }
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
