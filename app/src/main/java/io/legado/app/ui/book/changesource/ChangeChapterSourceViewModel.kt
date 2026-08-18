package io.legado.app.ui.book.changesource

import android.app.Application
import android.os.Bundle
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.ChangeSourceChapterProbe
import io.legado.app.data.entities.SearchBook
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ChapterSourceMatch
import io.legado.app.help.book.matchChapterSource
import io.legado.app.help.book.primaryStr
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.SourceConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.checkalgo.AskTimeout
import io.legado.app.model.checkalgo.ChangeBookSourceQuality
import io.legado.app.model.checkalgo.ChangeChapterVerify
import io.legado.app.model.checkalgo.ChangeSourceLog
import io.legado.app.model.review.ReviewCapability
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.internString
import io.legado.app.utils.mapParallel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min


internal sealed interface OriginalChaptersState {
    data object Loading : OriginalChaptersState
    data class Success(val chapters: List<BookChapter>) : OriginalChaptersState
    data class Error(val message: String) : OriginalChaptersState
}

internal sealed interface ChapterTocState {
    data object Idle : ChapterTocState
    data class Loading(val book: Book) : ChapterTocState
    data class Success(
        val book: Book,
        val toc: List<BookChapter>,
        val source: BookSource,
    ) : ChapterTocState

    data class Error(val throwable: Throwable) : ChapterTocState
}

internal sealed interface ChapterContentResult {
    data class Success(val content: String) : ChapterContentResult
    data class Error(val message: String) : ChapterContentResult
}

internal sealed interface ChapterCacheResult {
    data class Success(
        val cachedChapterIndex: Int,
        val nextChapter: BookChapter?,
        val targetPosition: Int,
        val automationSessionId: Long? = null,
    ) : ChapterCacheResult

    data class Error(val message: String) : ChapterCacheResult
}

internal sealed interface ChapterSourceAutomationPause {
    data object Ambiguous : ChapterSourceAutomationPause
    data object Missing : ChapterSourceAutomationPause
    data class ContentError(val message: String) : ChapterSourceAutomationPause
}

internal sealed interface ChapterSourceAutomationState {
    data object Idle : ChapterSourceAutomationState
    data class Ready(
        val sessionId: Long,
        val chapter: BookChapter,
        val position: Int,
        val total: Int,
    ) : ChapterSourceAutomationState

    data class Caching(
        val sessionId: Long,
        val chapter: BookChapter,
        val position: Int,
        val total: Int,
        val targetPositions: List<Int>,
    ) : ChapterSourceAutomationState

    data class Paused(
        val sessionId: Long,
        val chapter: BookChapter,
        val position: Int,
        val total: Int,
        val reason: ChapterSourceAutomationPause,
        val targetPositions: List<Int>,
    ) : ChapterSourceAutomationState

    data class Finished(val total: Int) : ChapterSourceAutomationState
}

internal class ChapterSourceAutomationSession(
    val id: Long,
    val originalBook: Book,
    val chapters: List<BookChapter>,
    val targetBook: Book,
    val targetToc: List<BookChapter>,
) {
    var position: Int = 0
        private set
    var stopAfterCurrent: Boolean = false
        private set

    val currentChapter: BookChapter?
        get() = chapters.getOrNull(position)

    val total: Int
        get() = chapters.size

    fun advance(expectedChapterIndex: Int): Boolean {
        if (currentChapter?.index != expectedChapterIndex) return false
        position++
        return true
    }

    fun requestStopAfterCurrent() {
        stopAfterCurrent = true
    }
}

internal fun chapterSourceAutomationRange(
    chapters: List<BookChapter>,
    start: Int,
    endInclusive: Int,
): List<BookChapter> {
    val contentChapters = chapters.filterNot { it.isVolume }
    if (start !in 1..contentChapters.size || endInclusive !in start..contentChapters.size) {
        return emptyList()
    }
    return contentChapters.subList(start - 1, endInclusive)
}

internal class ChapterSourceProgress {
    var chapterIndex: Int = 0
        private set
    var chapterTitle: String = ""
        private set
    var isFinished: Boolean = false
        private set
    private var initialized = false

    fun initialize(chapterIndex: Int, chapterTitle: String) {
        if (initialized) return
        initialized = true
        this.chapterIndex = chapterIndex
        this.chapterTitle = chapterTitle
    }

    fun update(chapterIndex: Int, chapterTitle: String) {
        this.chapterIndex = chapterIndex
        this.chapterTitle = chapterTitle
        isFinished = false
    }

    fun moveTo(chapter: BookChapter) {
        initialized = true
        isFinished = false
        chapterIndex = chapter.index
        chapterTitle = chapter.title
    }

    fun finish() {
        isFinished = true
    }

    fun currentChapter(chapters: List<BookChapter>): BookChapter? {
        return if (isFinished) null else chapters.firstOrNull { it.index == chapterIndex }
    }

    fun advance(chapters: List<BookChapter>, chapter: BookChapter): BookChapter? {
        val nextChapter = nextChapterSourceOriginal(chapters, chapter.index)
        isFinished = nextChapter == null
        if (nextChapter != null) {
            chapterIndex = nextChapter.index
            chapterTitle = nextChapter.title
        }
        return nextChapter
    }
}

@Suppress("MemberVisibilityCanBePrivate")
class ChangeChapterSourceViewModel(application: Application) :
    ChangeBookSourceViewModel(application) {

    companion object {
        private const val PROBE_TTL_MS = 86_400_000L
    }

    private val progress = ChapterSourceProgress()
    val chapterIndex: Int
        get() = progress.chapterIndex
    val chapterTitle: String
        get() = progress.chapterTitle
    internal val originalChaptersState = MutableLiveData<OriginalChaptersState>()
    internal val tocState = MutableLiveData<ChapterTocState>(ChapterTocState.Idle)
    val contentLoading = MutableLiveData(false)
    internal val contentResult = MutableLiveData<PendingEvent<ChapterContentResult>>()
    val batchCaching = MutableLiveData(false)
    internal val batchCacheResult = MutableLiveData<PendingEvent<ChapterCacheResult>>()
    internal val automationState = MutableLiveData<ChapterSourceAutomationState>(
        ChapterSourceAutomationState.Idle
    )
    private var originalBookUrl: String? = null
    private var originalChapters = emptyList<BookChapter>()
    private var originalChaptersTask: Coroutine<List<BookChapter>>? = null
    private var tocTask: Coroutine<Pair<List<BookChapter>, BookSource>>? = null
    private var contentTask: Coroutine<String>? = null
    private var cacheTask: Coroutine<Unit>? = null
    private var cacheCommitStarted = false
    private var automationGeneration = 0L
    private var automationSession: ChapterSourceAutomationSession? = null

    val currentOriginalChapter: BookChapter?
        get() = progress.currentChapter(originalChapters)

    val isBatchFinished: Boolean
        get() = progress.isFinished

    private val probeByOrigin = ConcurrentHashMap<String, ChangeSourceChapterProbe>()
    /** Session bodies for multi-source consensus (origin → processed chapter text). */
    private val probeContentSamples = ConcurrentHashMap<String, String>()
    private var verifyJob: Job? = null
    private val verifyGeneration = AtomicInteger(0)

    @Volatile
    var isChapterVerifying: Boolean = false
        private set

    val isAutomationActive: Boolean
        get() = automationSession != null

    override fun initData(arguments: Bundle?, book: Book?, fromReadBookActivity: Boolean) {
        super.initData(arguments, book, fromReadBookActivity)
        arguments?.let { bundle ->
            progress.initialize(
                chapterIndex = bundle.getInt("chapterIndex"),
                chapterTitle = bundle.getString("chapterTitle").orEmpty(),
            )
        }
    }

    /** Switch target chapter without clearing searchBooks; re-run incremental verify. */
    fun setChapter(index: Int, title: String) {
        progress.update(index, title)
        probeByOrigin.clear()
        probeContentSamples.clear()
        searchBooks.forEach { clearChapterProbeUi(it) }
        startChapterVerify()
    }

    private fun clearChapterProbeUi(book: SearchBook) {
        book.chapterWordCountText = null
        book.chapterWordCount = -1
        book.qualityVerdict = null
        book.qualityTags = emptyList()
        book.smartScore = -1
        book.probeChapterOrdinal = 0
        book.probeChapterTitle = null
    }

    private fun applyChapterMetricUi(
        book: SearchBook,
        measuredChars: Int,
        verdict: ChangeBookSourceQuality.QualityVerdict,
        qualityTag: String? = null,
        respondTimeMs: Int = book.respondTime,
    ) {
        book.chapterWordCount = measuredChars
        book.qualityVerdict = verdict
        book.qualityTags = listOfNotNull(qualityTag?.takeIf { it.isNotBlank() })
        val ordinal = (chapterIndex + 1).coerceAtLeast(1)
        book.probeChapterOrdinal = ordinal
        book.probeChapterTitle = chapterTitle
        book.chapterWordCountText = when {
            measuredChars >= 0 -> ChangeBookSourceQuality.metricLine(
                measuredChars = measuredChars,
                respondTimeMs = respondTimeMs,
                tocChapterCount = book.tocChapterCount,
                chapterOrdinal = ordinal,
                chapterTitle = chapterTitle,
            )
            else -> {
                val head = ChangeBookSourceQuality.buildMetricHead(
                    tocChapterCount = book.tocChapterCount,
                    chapterOrdinal = ordinal,
                    chapterTitle = chapterTitle,
                )
                val fail = getApplication<Application>()
                    .getString(R.string.change_source_chapter_content_fail)
                if (head != null) "$head\n$fail" else fail
            }
        }
        refreshSmartScore(book)
    }

    override fun onCachedSearchReady() {
        startChapterVerify()
    }

    override fun startSearch() {
        probeByOrigin.clear()
        probeContentSamples.clear()
        super.startSearch()
    }

    private fun notifySearchFinish(isEmpty: Boolean) {
        searchFinishCallback?.invoke(isEmpty)
        searchFinishData.postValue(PendingEvent(isEmpty))
    }

    override fun onSearchTaskFinished(isEmpty: Boolean) {
        if (isEmpty) {
            notifySearchFinish(true)
            return
        }
        startChapterVerify(afterSearch = true)
    }

    override fun refresh(): Boolean {
        val empty = super.refresh()
        if (!empty) {
            startChapterVerify()
        }
        return empty
    }

    override fun sortSearchBooks(books: List<SearchBook>): List<SearchBook> {
        return ChangeChapterVerify.sortSearchBooks(
            books = books,
            probeByOrigin = probeByOrigin,
            bookScore = { getBookScore(it) },
            sourceScore = { SourceConfig.getSourceScore(it) },
        )
    }

    override fun wordCountChapterIndex(chapters: List<BookChapter>): Int {
        return ChangeChapterVerify.alignIndex(chapterIndex, chapterTitle, chapters)
            ?: super.wordCountChapterIndex(chapters)
    }

    fun probeStatus(origin: String): ChangeSourceChapterProbe? = probeByOrigin[origin]

    fun startChapterVerify(afterSearch: Boolean = false) {
        verifyJob?.cancel()
        stopSearch()
        initSearchPoolProtected()
        val chapterKey = ChangeChapterVerify.chapterKey(chapterIndex, chapterTitle)
        val pool = searchPoolOrIo()
        val generation = verifyGeneration.incrementAndGet()
        verifyJob = viewModelScope.launch(pool) {
            clearEarlyStopped()
            isChapterVerifying = true
            try {
                searchStateData.postValue(true)
                updateChangeSourceProgress(
                    0,
                    getApplication<Application>().getString(R.string.change_source_verify_chapter)
                )
                loadProbesFromDb(chapterKey)
                applyProbeHintsToBooks()
                notifySearchAdapter()

                val candidates = searchBooks.filterNot {
                    ReviewCapability.isDedicatedReviewProvider(it.origin)
                }
                ChangeSourceLog.i(
                    "verify-start afterSearch=$afterSearch chapter=$chapterKey " +
                        "candidates=${candidates.size} cachedProbes=${probeByOrigin.size} " +
                        "threads=${AppConfig.threadCount}"
                )
                if (candidates.isEmpty()) {
                    ChangeSourceLog.i("verify-finish empty candidates")
                    if (afterSearch) notifySearchFinish(true)
                    return@launch
                }

                val parallelism = min(AppConfig.threadCount, 8).coerceAtLeast(1)
                val candidateOrigins = candidates.map { it.origin }.toHashSet()
                val toAlign = ChangeChapterVerify.prioritizeForTocAlign(
                    books = candidates,
                    probeByOrigin = probeByOrigin,
                    bookScore = { getBookScore(it) },
                    sourceScore = { SourceConfig.getSourceScore(it) },
                )
                val tocStop = AtomicBoolean(false)
                var alignedCount = 0
                flow {
                    toAlign.forEach { emit(it) }
                }.mapParallel(parallelism) { searchBook ->
                    if (tocStop.get()) return@mapParallel searchBook
                    alignOne(searchBook, chapterKey)
                    if (ChangeChapterVerify.shouldStopTocAlign(
                            ChangeChapterVerify.countUsableAlignments(
                                probeByOrigin,
                                candidateOrigins
                            )
                        )
                    ) {
                        tocStop.set(true)
                    }
                    searchBook
                }.catch {
                    AppLog.put("单章换源目录校验出错\n${it.localizedMessage}", it)
                }.collect {
                    alignedCount++
                    if (alignedCount % 4 == 0 || alignedCount == toAlign.size) {
                        updateChangeSourceProgress(
                            alignedCount,
                            getApplication<Application>().getString(R.string.change_source_verify_chapter)
                        )
                        notifySearchAdapter()
                    }
                }

                notifySearchAdapter()

                val ordered = sortSearchBooks(searchBooks.toList())
                val toProbe = ChangeChapterVerify.pickContentProbeOrigins(ordered, probeByOrigin)
                    .filterNot { reuseWordCountAsOk(it, chapterKey) }
                val contentParallel = min(
                    ChangeChapterVerify.CONTENT_PARALLEL,
                    AppConfig.threadCount.coerceAtLeast(1),
                )
                val contentStop = AtomicBoolean(
                    ChangeChapterVerify.shouldStopContentProbe(
                        ChangeChapterVerify.countOk(probeByOrigin, candidateOrigins)
                    )
                )
                val contentDone = AtomicInteger(0)
                flow {
                    toProbe.forEach { emit(it) }
                }.mapParallel(contentParallel) { searchBook ->
                    if (contentStop.get()) return@mapParallel null
                    contentProbeOne(searchBook, chapterKey, contentStop)
                    if (ChangeChapterVerify.shouldStopContentProbe(
                            ChangeChapterVerify.countOk(probeByOrigin, candidateOrigins)
                        )
                    ) {
                        contentStop.set(true)
                    }
                    searchBook
                }.catch {
                    AppLog.put("单章换源正文探测出错\n${it.localizedMessage}", it)
                }.collect { probed ->
                    if (probed == null) return@collect
                    val n = contentDone.incrementAndGet()
                    updateChangeSourceProgress(
                        n,
                        probed.originName.ifEmpty { probed.origin },
                    )
                    notifySearchAdapter()
                }
                applyMultiSourceConsensus(chapterKey)
                notifySearchAdapter()
                updateChangeSourceProgress(
                    contentDone.get().coerceAtLeast(1),
                    getApplication<Application>().getString(R.string.change_source_verify_done)
                )
                val okN = ChangeChapterVerify.countOk(probeByOrigin, candidateOrigins)
                ChangeSourceLog.i(
                    "verify-finish chapter=$chapterKey list=${searchBooks.size} " +
                        "aligned=$alignedCount contentProbed=${contentDone.get()} ok=$okN"
                )
                if (afterSearch) {
                    notifySearchFinish(searchBooks.isEmpty())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ChangeSourceLog.w("verify error ${e.localizedMessage}", e)
                AppLog.put("单章换源校验出错\n${e.localizedMessage}", e)
                if (afterSearch) notifySearchFinish(searchBooks.isEmpty())
            } finally {
                if (verifyGeneration.get() == generation) {
                    isChapterVerifying = false
                    searchStateData.postValue(false)
                }
            }
        }
        task = verifyJob
    }

    private fun searchPoolOrIo(): kotlinx.coroutines.CoroutineDispatcher {
        initSearchPoolProtected()
        return searchPool ?: IO
    }

    private fun loadProbesFromDb(chapterKey: String) {
        probeByOrigin.clear()
        probeContentSamples.clear()
        val minTime = System.currentTimeMillis() - PROBE_TTL_MS
        appDb.changeSourceChapterProbeDao.list(name, author, chapterKey).forEach {
            if (it.time >= minTime) {
                probeByOrigin[it.origin] = it
            }
        }
    }

    /**
     * After Top-K bodies are collected: demote origins that disagree with a trustworthy
     * agreeing cluster (reference-aware; see [ChangeChapterVerify.multiSourceOutlierOrigins]).
     */
    private fun applyMultiSourceConsensus(chapterKey: String) {
        val eval = contentEvalContext()
        val outliers = ChangeChapterVerify.multiSourceOutlierOrigins(
            samples = probeContentSamples.toMap(),
            referenceContent = eval.referenceContent,
            referenceTrusted = eval.referenceTrusted,
        )
        if (outliers.isEmpty()) return
        val app = getApplication<Application>()
        val msg = app.getString(R.string.change_source_chapter_hijack)
        outliers.forEach { origin ->
            upsertProbe(
                origin = origin,
                chapterKey = chapterKey,
                status = ChangeSourceChapterProbe.STATUS_CONTENT_FAIL,
                score = 0.0,
            )
            searchBooks.find { it.origin == origin }?.let { book ->
                val measured = book.chapterWordCount.coerceAtLeast(-1)
                applyChapterMetricUi(
                    book = book,
                    measuredChars = if (measured >= 0) measured else -1,
                    verdict = ChangeBookSourceQuality.QualityVerdict.Hijack,
                    qualityTag = msg,
                )
            }
            probeContentSamples.remove(origin)
        }
    }

    private fun applyProbeHintsToBooks() {
        val app = getApplication<Application>()
        searchBooks.forEach { book ->
            val probe = probeByOrigin[book.origin] ?: return@forEach
            when (probe.status) {
                ChangeSourceChapterProbe.STATUS_OK -> {
                    val n = probe.score.toInt()
                    applyChapterMetricUi(
                        book = book,
                        measuredChars = n,
                        verdict = ChangeBookSourceQuality.verdictFromContentQuality(
                            ChangeChapterVerify.ContentQuality.Ok(n),
                            n,
                        ),
                    )
                }

                ChangeSourceChapterProbe.STATUS_TOC_OK -> {
                    book.chapterWordCountText =
                        app.getString(R.string.change_source_pending_word)
                    book.qualityTags = listOf(app.getString(R.string.change_source_chapter_toc_ok))
                    book.qualityVerdict = ChangeBookSourceQuality.QualityVerdict.Pending
                    book.smartScore = -1
                }

                ChangeSourceChapterProbe.STATUS_NO_CHAPTER -> {
                    book.chapterWordCount = -1
                    book.chapterWordCountText =
                        app.getString(R.string.change_source_chapter_content_fail)
                    book.qualityVerdict = ChangeBookSourceQuality.QualityVerdict.FetchError
                    book.qualityTags =
                        listOf(app.getString(R.string.change_source_chapter_missing))
                    book.smartScore = ChangeBookSourceQuality.smartScore(
                        measuredChars = -1,
                        verdict = ChangeBookSourceQuality.QualityVerdict.FetchError,
                        userScore = getBookScore(book),
                    )
                }

                ChangeSourceChapterProbe.STATUS_CONTENT_FAIL -> {
                    val measured = book.chapterWordCount
                    if (measured >= 0 && book.qualityVerdict != null) {
                        // Keep split fields already written by contentProbeOne.
                        refreshSmartScore(book)
                    } else {
                        applyChapterMetricUi(
                            book = book,
                            measuredChars = -1,
                            verdict = ChangeBookSourceQuality.QualityVerdict.FetchError,
                            qualityTag = app.getString(R.string.change_source_chapter_content_fail),
                        )
                    }
                }
            }
        }
    }

    private suspend fun alignOne(
        searchBook: SearchBook,
        chapterKey: String,
    ) {
        currentCoroutineContext().ensureActive()
        when (probeByOrigin[searchBook.origin]?.status) {
            ChangeSourceChapterProbe.STATUS_OK,
            ChangeSourceChapterProbe.STATUS_NO_CHAPTER,
            ChangeSourceChapterProbe.STATUS_TOC_OK -> return
            // CONTENT_FAIL / missing: retry align
            else -> Unit
        }

        val book = bookMap[searchBook.toBook().primaryStr()] ?: searchBook.toBook().also {
            bookMap[it.primaryStr()] = it
        }
        val chapters = try {
            ensureToc(book, searchBook.origin) ?: run {
                markTransientTocFail(searchBook)
                return
            }
        } catch (e: TimeoutCancellationException) {
            markTransientTocFail(searchBook)
            return
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            markTransientTocFail(searchBook)
            return
        }
        searchBook.tocChapterCount = chapters.size

        val aligned = ChangeChapterVerify.alignResult(chapterIndex, chapterTitle, chapters)
        if (aligned == null) {
            upsertProbe(
                origin = searchBook.origin,
                chapterKey = chapterKey,
                status = ChangeSourceChapterProbe.STATUS_NO_CHAPTER,
                score = 0.0,
            )
            searchBook.chapterWordCountText =
                getApplication<Application>().getString(R.string.change_source_chapter_content_fail)
            searchBook.chapterWordCount = -1
            searchBook.qualityVerdict = ChangeBookSourceQuality.QualityVerdict.FetchError
            searchBook.qualityTags = listOf(
                getApplication<Application>().getString(R.string.change_source_chapter_missing),
            )
            searchBook.smartScore = ChangeBookSourceQuality.smartScore(
                measuredChars = -1,
                verdict = ChangeBookSourceQuality.QualityVerdict.FetchError,
                userScore = getBookScore(searchBook),
            )
        } else {
            upsertProbe(
                origin = searchBook.origin,
                chapterKey = chapterKey,
                status = ChangeSourceChapterProbe.STATUS_TOC_OK,
                score = aligned.quality,
            )
            searchBook.chapterWordCountText =
                getApplication<Application>().getString(R.string.change_source_pending_word)
            searchBook.qualityVerdict = ChangeBookSourceQuality.QualityVerdict.Pending
            searchBook.qualityTags = listOf(
                getApplication<Application>().getString(R.string.change_source_chapter_toc_ok),
            )
            searchBook.smartScore = -1
        }
    }

    /** Session-only; do not persist so the next open can retry transient network/toc errors. */
    private fun markTransientTocFail(searchBook: SearchBook) {
        applyChapterMetricUi(
            book = searchBook,
            measuredChars = -1,
            verdict = ChangeBookSourceQuality.QualityVerdict.FetchError,
        )
    }

    private fun contentEvalContext(): ChangeChapterVerify.ContentEvalContext {
        val book = oldBook
        val chapters = book?.let { appDb.bookChapterDao.getChapterList(it.bookUrl) }.orEmpty()
        val idx = if (book != null && chapters.isNotEmpty()) {
            ChangeChapterVerify.alignIndex(chapterIndex, chapterTitle, chapters)
                ?: chapterIndex.takeIf { it in chapters.indices }
        } else {
            null
        }
        val reference = if (book != null && idx != null) {
            BookHelp.getContent(book, chapters[idx])?.trim()?.takeIf { it.isNotEmpty() }
        } else {
            referenceChapterContent()
        }
        val localTitle = idx?.let { chapters[it].title } ?: referenceChapterTitle()
        val siblingLengths = if (book != null && idx != null) {
            chapters.asSequence()
                .drop(maxOf(0, idx - 2))
                .take(8)
                .mapNotNull { ch ->
                    BookHelp.getContent(book, ch)?.trim()?.length?.takeIf { it > 0 }
                }
                .toList()
        } else {
            emptyList()
        }
        val trust = ChangeChapterVerify.assessLocalReferenceTrust(
            localTitle = localTitle,
            referenceContent = reference,
            siblingBodyLengths = siblingLengths,
        )
        val expected = if (trust.trusted) {
            expectedChapterChars()
                ?: reference?.length?.takeIf { it >= ChangeChapterVerify.MIN_CONTENT_CHARS }
        } else {
            expectedChapterChars()
        }
        return ChangeChapterVerify.ContentEvalContext(
            expectedChars = expected,
            referenceContent = reference,
            referenceTrusted = trust.trusted,
        )
    }

    private fun referenceChapterTitle(): String? {
        val book = oldBook ?: return null
        val chapters = appDb.bookChapterDao.getChapterList(book.bookUrl)
        if (chapters.isEmpty()) return null
        val idx = ChangeChapterVerify.alignIndex(chapterIndex, chapterTitle, chapters)
            ?: chapterIndex.takeIf { it in chapters.indices }
            ?: return null
        return chapters[idx].title
    }

    /** Cached body of the chapter being replaced, when available on the current book. */
    private fun referenceChapterContent(): String? {
        val book = oldBook ?: return null
        val chapters = appDb.bookChapterDao.getChapterList(book.bookUrl)
        if (chapters.isEmpty()) return null
        val idx = ChangeChapterVerify.alignIndex(chapterIndex, chapterTitle, chapters)
            ?: chapterIndex.takeIf { it in chapters.indices }
            ?: return null
        return BookHelp.getContent(book, chapters[idx])?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Median of quality OK probe scores among current candidates; else null. */
    private fun expectedChapterChars(): Int? {
        val lengths = probeByOrigin.values.mapNotNull { probe ->
            if (probe.status == ChangeSourceChapterProbe.STATUS_OK &&
                probe.score >= ChangeChapterVerify.MIN_CONTENT_CHARS
            ) {
                probe.score.toInt()
            } else {
                null
            }
        }.sorted()
        if (lengths.isEmpty()) return null
        return lengths[lengths.size / 2]
    }

    /**
     * If search already loaded word-count for the aligned chapter, promote to OK without re-fetch.
     */
    private fun reuseWordCountAsOk(searchBook: SearchBook, chapterKey: String): Boolean {
        if (searchBook.chapterWordCount < ChangeChapterVerify.MIN_CONTENT_CHARS) return false
        if (probeByOrigin[searchBook.origin]?.status != ChangeSourceChapterProbe.STATUS_TOC_OK) {
            return false
        }
        // Word-count from search has no full text; only accept length gate.
        upsertProbe(
            origin = searchBook.origin,
            chapterKey = chapterKey,
            status = ChangeSourceChapterProbe.STATUS_OK,
            score = searchBook.chapterWordCount.toDouble(),
        )
        searchBook.probeChapterOrdinal = (chapterIndex + 1).coerceAtLeast(1)
        searchBook.probeChapterTitle = chapterTitle
        searchBook.chapterWordCountText = ChangeBookSourceQuality.metricLine(
            measuredChars = searchBook.chapterWordCount,
            respondTimeMs = searchBook.respondTime,
            tocChapterCount = searchBook.tocChapterCount,
            chapterOrdinal = searchBook.probeChapterOrdinal,
            chapterTitle = searchBook.probeChapterTitle,
        )
        searchBook.qualityVerdict = ChangeBookSourceQuality.verdictFromContentQuality(
            ChangeChapterVerify.ContentQuality.Ok(searchBook.chapterWordCount),
            searchBook.chapterWordCount,
        )
        searchBook.qualityTags = emptyList()
        refreshSmartScore(searchBook)
        return true
    }

    private suspend fun contentProbeOne(
        searchBook: SearchBook,
        chapterKey: String,
        contentStop: AtomicBoolean,
    ) {
        val book = bookMap[searchBook.toBook().primaryStr()] ?: searchBook.toBook().also {
            bookMap[it.primaryStr()] = it
        }
        val chapters = try {
            ensureToc(book, searchBook.origin) ?: return
        } catch (e: TimeoutCancellationException) {
            markContentFailUnlessStopped(searchBook, chapterKey, contentStop)
            return
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            markContentFailUnlessStopped(searchBook, chapterKey, contentStop)
            return
        }
        searchBook.tocChapterCount = chapters.size
        if (contentStop.get()) return
        val aligned = ChangeChapterVerify.alignResult(chapterIndex, chapterTitle, chapters) ?: return
        val chapter = chapters[aligned.index]
        val source = appDb.bookSourceDao.getBookSource(searchBook.origin) ?: return
        if (contentStop.get()) return
        try {
            val nextUrl = chapters.getOrNull(aligned.index + 1)?.url
            val content = withTimeout(AskTimeout.CHANGE_SOURCE_MS) {
                WebBook.getContentAwait(source, book, chapter, nextUrl, false)
            }
            if (contentStop.get()) return
            val processed = oldBook?.let {
                contentProcessor.getContent(it, chapter, content, false).toString()
            } ?: content
            val diag = ChangeChapterVerify.evaluateContentDiag(
                processed,
                contentEvalContext(),
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
            when (diag.quality) {
                is ChangeChapterVerify.ContentQuality.Ok -> {
                    upsertProbe(
                        origin = searchBook.origin,
                        chapterKey = chapterKey,
                        status = ChangeSourceChapterProbe.STATUS_OK,
                        score = measured.toDouble(),
                    )
                    probeContentSamples[searchBook.origin] = processed
                    applyChapterMetricUi(
                        book = searchBook,
                        measuredChars = measured,
                        verdict = verdict,
                    )
                }
                else -> {
                    markContentQualityFail(
                        searchBook,
                        chapterKey,
                        contentStop,
                        measuredChars = measured,
                        verdict = verdict,
                        qualityTag = tag,
                    )
                }
            }
        } catch (e: TimeoutCancellationException) {
            markContentFailUnlessStopped(searchBook, chapterKey, contentStop)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            markContentFailUnlessStopped(searchBook, chapterKey, contentStop)
        }
    }

    /** After early-stop, in-flight failures stay session-only (do not poison probe TTL). */
    private fun markContentFailUnlessStopped(
        searchBook: SearchBook,
        chapterKey: String,
        contentStop: AtomicBoolean,
    ) {
        markContentQualityFail(
            searchBook,
            chapterKey,
            contentStop,
            measuredChars = -1,
            verdict = ChangeBookSourceQuality.QualityVerdict.FetchError,
            qualityTag = getApplication<Application>()
                .getString(R.string.change_source_chapter_content_fail),
        )
    }

    private fun markContentQualityFail(
        searchBook: SearchBook,
        chapterKey: String,
        contentStop: AtomicBoolean,
        measuredChars: Int,
        verdict: ChangeBookSourceQuality.QualityVerdict,
        qualityTag: String?,
    ) {
        if (contentStop.get()) {
            applyChapterMetricUi(
                book = searchBook,
                measuredChars = measuredChars,
                verdict = verdict,
                qualityTag = qualityTag,
            )
            return
        }
        upsertProbe(
            origin = searchBook.origin,
            chapterKey = chapterKey,
            status = ChangeSourceChapterProbe.STATUS_CONTENT_FAIL,
            score = measuredChars.coerceAtLeast(0).toDouble(),
        )
        applyChapterMetricUi(
            book = searchBook,
            measuredChars = measuredChars,
            verdict = verdict,
            qualityTag = qualityTag,
        )
    }

    private suspend fun ensureToc(
        book: Book,
        origin: String,
    ): List<BookChapter>? {
        tocMap[book.primaryStr()]?.let { return it }
        val source = appDb.bookSourceDao.getBookSource(origin) ?: return null
        return withTimeout(AskTimeout.CHANGE_SOURCE_MS) {
            if (book.tocUrl.isEmpty()) {
                WebBook.getBookInfoAwait(source, book)
            }
            WebBook.getChapterListAwait(source, book).getOrThrow()
        }.also { toc ->
            for (chapter in toc) {
                chapter.internString()
            }
            // Always cache for chapter verify so Top-K content does not re-fetch.
            tocMap[book.primaryStr()] = toc
            putToc(book, toc)
        }
    }

    private fun upsertProbe(origin: String, chapterKey: String, status: String, score: Double) {
        val row = ChangeSourceChapterProbe(
            name = name,
            author = author,
            origin = origin,
            chapterKey = chapterKey,
            status = status,
            score = score,
            time = System.currentTimeMillis(),
        )
        probeByOrigin[origin] = row
        appDb.changeSourceChapterProbeDao.upsert(row)
    }


    fun loadContent(
        book: Book,
        chapter: BookChapter,
        nextChapterUrl: String?,
    ) {
        contentTask?.cancel()
        contentLoading.value = true
        contentTask = execute {
            val bookSource = appDb.bookSourceDao.getBookSource(book.origin)
                ?: throw NoStackTraceException("书源不存在")
            WebBook.getContentAwait(bookSource, book, chapter, nextChapterUrl, false)
        }.onSuccess {
            contentTask = null
            contentLoading.value = false
            contentResult.value = PendingEvent(ChapterContentResult.Success(it))
        }.onError {
            contentTask = null
            contentLoading.value = false
            contentResult.value = PendingEvent(
                ChapterContentResult.Error(it.localizedMessage ?: "获取正文出错")
            )
        }
    }

    fun loadOriginalChapters(bookUrl: String) {
        val state = originalChaptersState.value
        if (originalBookUrl == bookUrl &&
            (state is OriginalChaptersState.Loading || state is OriginalChaptersState.Success)
        ) {
            return
        }
        originalBookUrl = bookUrl
        originalChaptersTask?.cancel()
        originalChaptersState.value = OriginalChaptersState.Loading
        originalChaptersTask = execute {
            appDb.bookChapterDao.getChapterList(bookUrl)
        }.onSuccess { chapters ->
            originalChaptersTask = null
            originalChapters = chapters
            originalChaptersState.value = OriginalChaptersState.Success(chapters)
        }.onError {
            originalChaptersTask = null
            originalChaptersState.value = OriginalChaptersState.Error(
                it.localizedMessage ?: "获取目录出错"
            )
        }
    }

    fun loadToc(book: Book) {
        if (isAutomationActive || batchCaching.value == true) return
        cancelContent()
        tocTask?.cancel()
        tocState.value = ChapterTocState.Loading(book)
        tocTask = getToc(book, { toc, source ->
            tocTask = null
            tocState.value = ChapterTocState.Success(book, toc, source)
        }, { throwable ->
            tocTask = null
            tocState.value = ChapterTocState.Error(throwable)
        })
    }

    fun clearToc() {
        cancelContent()
        tocTask?.cancel()
        tocTask = null
        tocState.value = ChapterTocState.Idle
    }

    private fun cancelContent() {
        contentTask?.cancel()
        contentTask = null
        contentLoading.value = false
    }

    fun cacheContents(
        sourceBook: Book,
        sourceChapters: List<Pair<BookChapter, String?>>,
        originalBook: Book,
        originalChapter: BookChapter,
        targetPosition: Int,
    ) {
        cacheContents(
            sourceBook = sourceBook,
            sourceChapters = sourceChapters,
            originalBook = originalBook,
            originalChapter = originalChapter,
            targetPosition = targetPosition,
            automationSessionId = null,
            automationTargetPositions = emptyList(),
        )
    }

    private fun cacheContents(
        sourceBook: Book,
        sourceChapters: List<Pair<BookChapter, String?>>,
        originalBook: Book,
        originalChapter: BookChapter,
        targetPosition: Int,
        automationSessionId: Long?,
        automationTargetPositions: List<Int>,
    ) {
        if (batchCaching.value == true) return
        cacheCommitStarted = false
        batchCaching.value = true
        cacheTask = execute {
            val bookSource = appDb.bookSourceDao.getBookSource(sourceBook.origin)
                ?: throw NoStackTraceException("书源不存在")
            val contents = sourceChapters.map { (chapter, nextChapterUrl) ->
                WebBook.getContentAwait(
                    bookSource,
                    sourceBook,
                    chapter,
                    nextChapterUrl,
                    false,
                )
            }
            val mergedContent = mergeChapterSourceContents(contents)
            if (mergedContent.isBlank()) throw NoStackTraceException("正文为空")
            ensureActive()
            withContext(Main) {
                cacheCommitStarted = true
            }
            withContext(NonCancellable) {
                BookHelp.saveText(
                    originalBook,
                    originalChapter,
                    mergedContent,
                    saveChapterMetadata = true,
                )
                withContext(Main) {
                    cacheTask = null
                    cacheCommitStarted = false
                    batchCaching.value = false
                    batchCacheResult.value = PendingEvent(
                        ChapterCacheResult.Success(
                            cachedChapterIndex = originalChapter.index,
                            nextChapter = if (automationSessionId == null) {
                                advanceOriginalChapter(originalChapter)
                            } else {
                                null
                            },
                            targetPosition = targetPosition,
                            automationSessionId = automationSessionId,
                        )
                    )
                }
            }
        }.onError { throwable ->
            cacheTask = null
            cacheCommitStarted = false
            batchCaching.value = false
            val message = throwable.localizedMessage ?: "获取正文出错"
            if (automationSessionId == null || pauseAutomationAfterError(
                    automationSessionId,
                    originalChapter,
                    automationTargetPositions,
                    message,
                )
            ) {
                batchCacheResult.value = PendingEvent(
                    ChapterCacheResult.Error(message)
                )
            }
        }
    }

    fun automationRangeDefaults(): IntRange? {
        val contentChapters = originalChapters.filterNot { it.isVolume }
        val currentPosition = contentChapters.indexOfFirst { it.index == progress.chapterIndex }
        if (currentPosition < 0) return null
        return (currentPosition + 1)..contentChapters.size
    }

    fun startAutomation(
        originalBook: Book,
        targetBook: Book,
        targetToc: List<BookChapter>,
        start: Int,
        endInclusive: Int,
    ): Boolean {
        if (isAutomationActive || batchCaching.value == true) return false
        val chapters = chapterSourceAutomationRange(originalChapters, start, endInclusive)
        if (chapters.isEmpty()) return false
        val session = ChapterSourceAutomationSession(
            id = ++automationGeneration,
            originalBook = originalBook.copy(),
            chapters = chapters.map { it.copy() },
            targetBook = targetBook.copy(),
            targetToc = targetToc.map { it.copy() },
        )
        automationSession = session
        progress.moveTo(requireNotNull(session.currentChapter))
        automationState.value = readyAutomationState(session)
        return true
    }

    fun runNextAutomationIfReady() {
        val state = automationState.value as? ChapterSourceAutomationState.Ready ?: return
        val session = automationSession?.takeIf { it.id == state.sessionId } ?: return
        val chapter = session.currentChapter?.takeIf { it.index == state.chapter.index } ?: return
        when (val match = matchChapterSource(chapter, session.targetToc)) {
            is ChapterSourceMatch.Unique -> cacheAutomationPositions(
                session,
                chapter,
                listOf(match.targetPosition),
            )

            is ChapterSourceMatch.Ambiguous -> pauseAutomation(
                session,
                chapter,
                ChapterSourceAutomationPause.Ambiguous,
                match.targetPositions,
            )

            ChapterSourceMatch.Missing -> pauseAutomation(
                session,
                chapter,
                ChapterSourceAutomationPause.Missing,
                emptyList(),
            )
        }
    }

    fun cacheAutomationSelection(targetPositions: List<Int>) {
        val state = automationState.value as? ChapterSourceAutomationState.Paused ?: return
        val session = automationSession?.takeIf { it.id == state.sessionId } ?: return
        val chapter = session.currentChapter?.takeIf { it.index == state.chapter.index } ?: return
        val positions = targetPositions.distinct().sorted().filter { position ->
            session.targetToc.getOrNull(position)?.isVolume == false
        }
        if (positions.isEmpty()) return
        cacheAutomationPositions(session, chapter, positions)
    }

    fun acknowledgeAutomationCache(sessionId: Long, chapterIndex: Int): Boolean {
        val state = automationState.value as? ChapterSourceAutomationState.Caching ?: return false
        val session = automationSession?.takeIf { it.id == sessionId } ?: return false
        return state.sessionId == sessionId && advanceAutomation(session, chapterIndex)
    }

    fun skipAutomationChapter(): Boolean {
        val state = automationState.value
        val sessionId = when (state) {
            is ChapterSourceAutomationState.Ready -> state.sessionId
            is ChapterSourceAutomationState.Paused -> state.sessionId
            else -> return false
        }
        val session = automationSession?.takeIf { it.id == sessionId } ?: return false
        val chapter = session.currentChapter ?: return false
        return advanceAutomation(session, chapter.index)
    }

    private fun advanceAutomation(
        session: ChapterSourceAutomationSession,
        chapterIndex: Int,
    ): Boolean {
        if (!session.advance(chapterIndex)) return false
        val nextChapter = session.currentChapter
        if (nextChapter == null) {
            progress.finish()
            automationSession = null
            automationState.value = ChapterSourceAutomationState.Finished(session.total)
        } else if (session.stopAfterCurrent) {
            progress.moveTo(nextChapter)
            automationSession = null
            automationState.value = ChapterSourceAutomationState.Idle
        } else {
            progress.moveTo(nextChapter)
            automationState.value = readyAutomationState(session)
        }
        return true
    }

    fun stopAutomation() {
        if (cacheCommitStarted) {
            automationSession?.requestStopAfterCurrent()
            return
        }
        automationGeneration++
        automationSession = null
        automationState.value = ChapterSourceAutomationState.Idle
        cancelCacheContents()
    }

    private fun cacheAutomationPositions(
        session: ChapterSourceAutomationSession,
        chapter: BookChapter,
        targetPositions: List<Int>,
    ) {
        if (batchCaching.value == true) return
        val sourceChapters = targetPositions.mapNotNull { position ->
            session.targetToc.getOrNull(position)?.takeUnless { it.isVolume }?.let {
                it to session.targetToc.getOrNull(position + 1)?.url
            }
        }
        if (sourceChapters.size != targetPositions.size) return
        automationState.value = ChapterSourceAutomationState.Caching(
            session.id,
            chapter,
            session.position,
            session.total,
            targetPositions,
        )
        cacheContents(
            sourceBook = session.targetBook,
            sourceChapters = sourceChapters,
            originalBook = session.originalBook,
            originalChapter = chapter,
            targetPosition = targetPositions.last() + 1,
            automationSessionId = session.id,
            automationTargetPositions = targetPositions,
        )
    }

    private fun pauseAutomation(
        session: ChapterSourceAutomationSession,
        chapter: BookChapter,
        reason: ChapterSourceAutomationPause,
        targetPositions: List<Int>,
    ) {
        automationState.value = ChapterSourceAutomationState.Paused(
            session.id,
            chapter,
            session.position,
            session.total,
            reason,
            targetPositions,
        )
    }

    private fun pauseAutomationAfterError(
        sessionId: Long,
        chapter: BookChapter,
        targetPositions: List<Int>,
        message: String,
    ): Boolean {
        val session = automationSession?.takeIf { it.id == sessionId } ?: return false
        val currentChapter = session.currentChapter?.takeIf { it.index == chapter.index }
            ?: return false
        pauseAutomation(
            session,
            currentChapter,
            ChapterSourceAutomationPause.ContentError(message),
            targetPositions,
        )
        return true
    }

    private fun readyAutomationState(
        session: ChapterSourceAutomationSession,
    ): ChapterSourceAutomationState.Ready {
        return ChapterSourceAutomationState.Ready(
            session.id,
            requireNotNull(session.currentChapter),
            session.position,
            session.total,
        )
    }

    fun cancelCacheContents() {
        if (cacheCommitStarted) return
        cacheTask?.cancel()
        cacheTask = null
        batchCaching.value = false
    }

    fun advanceOriginalChapter(chapter: BookChapter): BookChapter? {
        return progress.advance(originalChapters, chapter)
    }

}

internal fun mergeChapterSourceContents(contents: List<String>): String = buildString {
    contents.forEachIndexed { index, content ->
        if (index > 0) {
            val previous = contents[index - 1]
            val lastContentIndex = previous.indexOfLast { !it.isWhitespace() }
            if (lastContentIndex >= 0 && previous[lastContentIndex] in "。！？.!?" &&
                (lastContentIndex + 1 until previous.length)
                    .none { previous[it] in "\r\n" }
            ) {
                append('\n')
            }
        }
        append(content)
    }
}

internal fun nextChapterSourceOriginal(
    chapters: List<BookChapter>,
    currentIndex: Int,
): BookChapter? = chapters.firstOrNull { !it.isVolume && it.index > currentIndex }

