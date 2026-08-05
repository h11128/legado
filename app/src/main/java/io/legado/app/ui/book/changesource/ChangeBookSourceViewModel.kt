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
    val bookMap = ConcurrentHashMap<String, Book>()
    val searchDataFlow = callbackFlow {

        searchCallback = object : SourceCallback {

            override fun searchSuccess(searchBook: SearchBook) {
                searchBook.releaseHtmlData()
                appDb.searchBookDao.insert(searchBook)
                val accepted = synchronized(searchBooks) {
                    val idx = searchBooks.indexOfFirst { it.origin == searchBook.origin }
                    when {
                        idx >= 0 -> {
                            searchBooks[idx] = searchBook
                            true
                        }
                        screenKey.isEmpty() || searchBook.name.contains(screenKey) -> {
                            searchBooks.add(searchBook)
                            true
                        }
                        else -> false
                    }
                }
                if (!accepted) return
                val size = searchBooks.size
                listPublishCount.incrementAndGet()
                val tier = ChangeBookSourceQuality.contentSortTier(
                    chapterWordCount = searchBook.chapterWordCount,
                    wordCountText = searchBook.chapterWordCountText,
                    softFailed = searchBook.origin in sessionSoftFail,
                )
                ChangeSourceLog.i(
                    "list+ size=$size origin=${searchBook.origin} " +
                        "name=${searchBook.originName} words=${searchBook.chapterWordCount} " +
                        "tier=$tier respondMs=${searchBook.respondTime} " +
                        "latest=${searchBook.latestChapterTitle?.take(24) ?: ""}"
                )
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
            inFlight = 0,
            concurrency = threadCount(),
            deepInFlight = deepInFlightNames.size,
            label = label,
            qualityOk = qualityOkCount.get(),
            earlyStopped = earlyStopped.get(),
            finished = false,
        )
    }

    override fun onCleared() {
        super.onCleared()
        (searchPool as? Closeable)?.close()
        searchPool = null
        searchPoolSize = 0
        (deepPool as? Closeable)?.close()
        deepPool = null
        deepPoolSize = 0
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
        if (deepPool != null && deepPoolSize == n) return
        (deepPool as? Closeable)?.close()
        deepPoolSize = n
        deepPool = Dispatchers.IO.limitedParallelism(n)
    }

    companion object {
        /** Max concurrent toc/content probes after ask releases the slot. */
        const val DEEP_PARALLEL_CAP = 16
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
            wordCountEvalByLocalIndex.clear()
            probeContentSamples.clear()
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
            initSearchPool()
            initDeepPool()
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
            wordCountEvalByLocalIndex.clear()
            probeContentSamples.clear()
            qualityTiers.clear()
            sessionSoftFail.clear()
            probingNames.clear()
            deepInFlightNames.clear()
            deepJobs.clear()
            completedProbeCount.set(0)
            qualityOkCount.set(0)
            earlyStopped.set(false)
            _changeSourceProgress.value = ChangeSourceProgressUi()
            bookSourceParts.add(appDb.bookSourceDao.getBookSourcePart(origin)!!)
            searchBooks.removeIf { it.origin == origin }
            initSearchPool()
            initDeepPool()
            search()
        }
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
        val probingLabel = listOf(askLabel, deepLabel).filter { it.isNotEmpty() }.joinToString(" · ")
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

    private fun search() {
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
                            )
                            "${b.originName}(w=${b.chapterWordCount},t=$tier,ms=${b.respondTime})"
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
        val startTime = System.currentTimeMillis()
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
            searchHitCount.incrementAndGet()
            RespondTimeUpdater.noteSuccessAndMaybeFlush(
                source.bookSourceUrl,
                searchElapsed,
                source.respondTime,
            )
            val deep = loadInfo || loadToc || loadWordCount
            ChangeSourceLog.i(
                "hit origin=${source.bookSourceUrl} name=${source.bookSourceName} " +
                    "searchMs=$searchElapsed results=${resultBooks.size} deep=$deep " +
                    "list=${searchBooks.size} inFlight=${probingNames.size}"
            )
            if (!deep) {
                resultBooks.forEach { searchBook ->
                    searchBook.respondTime = searchElapsed.toInt()
                    publishSearchBook(searchBook)
                }
                return@withTimeoutOrNull true
            }
            ChangeSourceLog.i(
                "deep-schedule origin=${source.bookSourceUrl} " +
                    "loadInfo=$loadInfo loadToc=$loadToc loadWordCount=$loadWordCount"
            )
            // Early list so UI fills while ask slots keep draining empties.
            // Do NOT skip on latestMatchesLocal==false: lagging mirrors (same book, older tip)
            // must still deep-probe; wrong books fail content ref_sim and are dropped.
            resultBooks.forEach { searchBook ->
                searchBook.respondTime = searchElapsed.toInt()
                searchBook.chapterWordCount = 0
                searchBook.chapterWordCountText =
                    getApplication<Application>().getString(R.string.change_source_pending_word)
                publishSearchBook(searchBook)
            }
            val pool = deepPool ?: IO
            val job = viewModelScope.launch(pool) {
                deepInFlightNames.add(source.bookSourceUrl)
                publishProgress()
                try {
                    if (earlyStopped.get()) return@launch
                    resultBooks.forEach { searchBook ->
                        currentCoroutineContext().ensureActive()
                        if (earlyStopped.get()) return@launch
                        loadBookInfo(source, searchBook.toBook())
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ChangeSourceLog.i(
                        "deep-error origin=${source.bookSourceUrl} ${e.localizedMessage}"
                    )
                    dropSearchBookOrigin(source.bookSourceUrl, "deep-error")
                    noteAskMiss(source.bookSourceUrl, "error", processDemote = true)
                } finally {
                    deepInFlightNames.remove(source.bookSourceUrl)
                    publishProgress()
                }
            }
            deepJobs.add(job)
            job.invokeOnCompletion { deepJobs.remove(job) }
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
            dropSearchBookOrigin(book.origin, "empty-toc")
            noteAskMiss(book.origin, "content-bad", processDemote = false)
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
            val evalCtx = bookChangeContentEvalContext(chapterIndex, bookChapter.title)
            val diag = ChangeChapterVerify.evaluateContentDiag(content, evalCtx)
            ChangeSourceLog.i(
                "phase word-eval origin=${source.bookSourceUrl} reason=${diag.reason} " +
                    "len=${diag.contentLen} stitch=${diag.stitch} " +
                    "refSim=${diag.refSim?.let { "%.4f".format(it) } ?: "-"} " +
                    "expected=${diag.expectedChars ?: "-"} " +
                    "chapter=[$chapterIndex] ${title.take(24)}"
            )
            when (val quality = diag.quality) {
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
            ChangeSourceLog.i(
                "phase word-eval origin=${source.bookSourceUrl} reason=fetch_error " +
                    "err=${t.javaClass.simpleName}:${t.localizedMessage?.take(80)}"
            )
            -1 to "[${chapterIndex + 1}] ${title}\n获取字数失败：${t.localizedMessage}"
        }
        val endTime = System.currentTimeMillis()
        val searchBook = book.toSearchBook().apply {
            chapterWordCountText = pair.second
            chapterWordCount = pair.first
            respondTime = (endTime - startTime).toInt()
        }
        val tier = ChangeBookSourceQuality.contentSortTier(
            chapterWordCount = pair.first,
            wordCountText = pair.second,
            softFailed = false,
        )
        ChangeSourceLog.i(
            "phase word origin=${source.bookSourceUrl} chars=${pair.first} " +
                "tier=$tier ms=${endTime - startTime} " +
                "ok=${ChangeBookSourceQuality.isQualityOkWordCount(pair.first)} " +
                "list=${searchBooks.size}"
        )
        if (pair.first < 0) {
            // Do not keep content-bad rows in the result list (session evidence: 140 bad flooded UI).
            dropSearchBookOrigin(searchBook.origin, "content-bad")
            noteAskMiss(searchBook.origin, "content-bad", processDemote = false)
            mergeTier(searchBook.origin, ChangeBookSourceQuality.TIER_CONTENT_BAD)
        } else {
            if (ChangeBookSourceQuality.isQualityOkWordCount(pair.first) && processedContent != null) {
                probeContentSamples[searchBook.origin] = processedContent
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
                ChangeBookSourceQuality.isQualityOkWordCount(it.chapterWordCount)
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
        probeContentSamples.remove(origin)
        // Consensus outliers leave the visible list; drop rolls back qualityOk if needed.
        dropSearchBookOrigin(origin, "consensus:$badge")
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
        val ctx = ChangeChapterVerify.ContentEvalContext(
            expectedChars = reference?.length?.takeIf {
                it >= ChangeChapterVerify.MIN_CONTENT_CHARS
            },
            referenceContent = reference,
        )
        wordCountEvalByLocalIndex[idx] = ctx
        val durIdx = wordCountChapterIndex(localChapters).coerceIn(0, localChapters.lastIndex)
        if (wordCountEvalContext == null || idx == durIdx) {
            wordCountEvalContext = ctx
        }
        ChangeSourceLog.i(
            "ref-cache localIdx=$idx title=${localChapter.title.take(24)} " +
                "refLen=${reference?.length ?: 0} expected=${ctx.expectedChars ?: "-"}"
        )
        return ctx
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
        val wasActive = task?.isActive == true || deepJobs.any { it.isActive }
        task?.cancel()
        deepJobs.forEach { it.cancel() }
        deepJobs.clear()
        deepInFlightNames.clear()
        // Do not close searchPool here: onCompletion may still flush on it;
        // Pool is reused while threadCount() is unchanged; resized in initSearchPool().
        // onCleared() attempts Closeable.close() (Executor pools); limitedParallelism is a no-op.
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
