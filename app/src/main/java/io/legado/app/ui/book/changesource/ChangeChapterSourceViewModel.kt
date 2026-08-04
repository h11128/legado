package io.legado.app.ui.book.changesource

import android.app.Application
import android.os.Bundle
import androidx.lifecycle.viewModelScope
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.ChangeSourceChapterProbe
import io.legado.app.data.entities.SearchBook
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.primaryStr
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.SourceConfig
import io.legado.app.model.checkalgo.AskSourcePrefetch
import io.legado.app.model.checkalgo.AskTimeout
import io.legado.app.model.checkalgo.ChangeChapterVerify
import io.legado.app.model.checkalgo.CheckHostTokenBucket
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.internString
import io.legado.app.utils.mapParallel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

@Suppress("MemberVisibilityCanBePrivate")
class ChangeChapterSourceViewModel(application: Application) :
    ChangeBookSourceViewModel(application) {

    companion object {
        private const val PROBE_TTL_MS = 86_400_000L
    }

    var chapterIndex: Int = 0
    var chapterTitle: String = ""

    private val probeByOrigin = ConcurrentHashMap<String, ChangeSourceChapterProbe>()
    private var verifyJob: Job? = null
    private val verifyGeneration = AtomicInteger(0)

    @Volatile
    var isChapterVerifying: Boolean = false
        private set

    override fun initData(arguments: Bundle?, book: Book?, fromReadBookActivity: Boolean) {
        super.initData(arguments, book, fromReadBookActivity)
        arguments?.let { bundle ->
            bundle.getString("chapterTitle")?.let {
                chapterTitle = it
            }
            chapterIndex = bundle.getInt("chapterIndex")
        }
    }

    /** Switch target chapter without clearing searchBooks; re-run incremental verify. */
    fun setChapter(index: Int, title: String) {
        chapterIndex = index
        chapterTitle = title
        probeByOrigin.clear()
        searchBooks.forEach {
            it.chapterWordCountText = null
            it.chapterWordCount = -1
        }
        startChapterVerify()
    }

    override fun onCachedSearchReady() {
        startChapterVerify()
    }

    override fun startSearch() {
        probeByOrigin.clear()
        super.startSearch()
    }

    override fun onSearchTaskFinished(isEmpty: Boolean) {
        if (isEmpty) {
            searchFinishCallback?.invoke(true)
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

                val candidates = searchBooks.toList()
                if (candidates.isEmpty()) {
                    if (afterSearch) searchFinishCallback?.invoke(true)
                    return@launch
                }

                val hostBucket = CheckHostTokenBucket()
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
                    alignOne(searchBook, chapterKey, hostBucket)
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
                    contentProbeOne(searchBook, chapterKey, hostBucket, contentStop)
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
                updateChangeSourceProgress(
                    contentDone.get().coerceAtLeast(1),
                    getApplication<Application>().getString(R.string.change_source_verify_done)
                )
                if (afterSearch) {
                    searchFinishCallback?.invoke(searchBooks.isEmpty())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.put("单章换源校验出错\n${e.localizedMessage}", e)
                if (afterSearch) searchFinishCallback?.invoke(searchBooks.isEmpty())
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
        val minTime = System.currentTimeMillis() - PROBE_TTL_MS
        appDb.changeSourceChapterProbeDao.list(name, author, chapterKey).forEach {
            if (it.time >= minTime) {
                probeByOrigin[it.origin] = it
            }
        }
    }

    private fun applyProbeHintsToBooks() {
        val app = getApplication<Application>()
        searchBooks.forEach { book ->
            val probe = probeByOrigin[book.origin] ?: return@forEach
            when (probe.status) {
                ChangeSourceChapterProbe.STATUS_OK -> {
                    book.chapterWordCountText = app.getString(
                        R.string.change_source_chapter_ok,
                        probe.score.toInt()
                    )
                    book.chapterWordCount = probe.score.toInt()
                }

                ChangeSourceChapterProbe.STATUS_TOC_OK -> {
                    book.chapterWordCountText =
                        app.getString(R.string.change_source_chapter_toc_ok)
                }

                ChangeSourceChapterProbe.STATUS_NO_CHAPTER -> {
                    book.chapterWordCountText =
                        app.getString(R.string.change_source_chapter_missing)
                    book.chapterWordCount = -1
                }

                ChangeSourceChapterProbe.STATUS_CONTENT_FAIL -> {
                    book.chapterWordCountText =
                        app.getString(R.string.change_source_chapter_content_fail)
                    book.chapterWordCount = -1
                }
            }
        }
    }

    private suspend fun alignOne(
        searchBook: SearchBook,
        chapterKey: String,
        hostBucket: CheckHostTokenBucket,
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
            ensureToc(book, searchBook.origin, hostBucket) ?: run {
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

        val aligned = ChangeChapterVerify.alignResult(chapterIndex, chapterTitle, chapters)
        if (aligned == null) {
            upsertProbe(
                origin = searchBook.origin,
                chapterKey = chapterKey,
                status = ChangeSourceChapterProbe.STATUS_NO_CHAPTER,
                score = 0.0,
            )
            searchBook.chapterWordCountText =
                getApplication<Application>().getString(R.string.change_source_chapter_missing)
        } else {
            upsertProbe(
                origin = searchBook.origin,
                chapterKey = chapterKey,
                status = ChangeSourceChapterProbe.STATUS_TOC_OK,
                score = aligned.quality,
            )
            searchBook.chapterWordCountText =
                getApplication<Application>().getString(R.string.change_source_chapter_toc_ok)
        }
    }

    /** Session-only; do not persist so the next open can retry transient network/toc errors. */
    private fun markTransientTocFail(searchBook: SearchBook) {
        searchBook.chapterWordCountText =
            getApplication<Application>().getString(R.string.change_source_chapter_content_fail)
        searchBook.chapterWordCount = -1
    }

    private fun contentEvalContext(): ChangeChapterVerify.ContentEvalContext {
        return ChangeChapterVerify.ContentEvalContext(
            bookName = name,
            expectedChars = expectedChapterChars(),
        )
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
        searchBook.chapterWordCountText = getApplication<Application>().getString(
            R.string.change_source_chapter_ok,
            searchBook.chapterWordCount
        )
        return true
    }

    private suspend fun contentProbeOne(
        searchBook: SearchBook,
        chapterKey: String,
        hostBucket: CheckHostTokenBucket,
        contentStop: AtomicBoolean,
    ) {
        val book = bookMap[searchBook.toBook().primaryStr()] ?: searchBook.toBook().also {
            bookMap[it.primaryStr()] = it
        }
        val chapters = try {
            ensureToc(book, searchBook.origin, hostBucket) ?: return
        } catch (e: TimeoutCancellationException) {
            markContentFailUnlessStopped(searchBook, chapterKey, contentStop)
            return
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            markContentFailUnlessStopped(searchBook, chapterKey, contentStop)
            return
        }
        if (contentStop.get()) return
        val aligned = ChangeChapterVerify.alignResult(chapterIndex, chapterTitle, chapters) ?: return
        val chapter = chapters[aligned.index]
        val source = appDb.bookSourceDao.getBookSource(searchBook.origin) ?: return
        val host = AskSourcePrefetch.hostOf(searchBook.origin)
        if (host.isNotEmpty()) hostBucket.acquire(host)
        if (contentStop.get()) return
        try {
            val nextUrl = chapters.getOrNull(aligned.index + 1)?.url
            val content = withTimeout(AskTimeout.SUCCESS_MS) {
                WebBook.getContentAwait(source, book, chapter, nextUrl, false)
            }
            if (contentStop.get()) return
            val processed = oldBook?.let {
                contentProcessor.getContent(it, chapter, content, false).toString()
            } ?: content
            when (val quality = ChangeChapterVerify.evaluateContent(
                processed,
                contentEvalContext(),
            )) {
                is ChangeChapterVerify.ContentQuality.Ok -> {
                    upsertProbe(
                        origin = searchBook.origin,
                        chapterKey = chapterKey,
                        status = ChangeSourceChapterProbe.STATUS_OK,
                        score = quality.length.toDouble(),
                    )
                    searchBook.chapterWordCount = quality.length
                    searchBook.chapterWordCountText = getApplication<Application>().getString(
                        R.string.change_source_chapter_ok,
                        quality.length
                    )
                }
                ChangeChapterVerify.ContentQuality.TooShort -> {
                    markContentQualityFail(
                        searchBook,
                        chapterKey,
                        contentStop,
                        R.string.change_source_chapter_too_short,
                    )
                }
                ChangeChapterVerify.ContentQuality.AntiTheft -> {
                    markContentQualityFail(
                        searchBook,
                        chapterKey,
                        contentStop,
                        R.string.change_source_chapter_anti_theft,
                    )
                }
                ChangeChapterVerify.ContentQuality.Hijack -> {
                    markContentQualityFail(
                        searchBook,
                        chapterKey,
                        contentStop,
                        R.string.change_source_chapter_hijack,
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
            R.string.change_source_chapter_content_fail,
        )
    }

    private fun markContentQualityFail(
        searchBook: SearchBook,
        chapterKey: String,
        contentStop: AtomicBoolean,
        messageRes: Int,
    ) {
        val msg = getApplication<Application>().getString(messageRes)
        if (contentStop.get()) {
            searchBook.chapterWordCountText = msg
            searchBook.chapterWordCount = -1
            return
        }
        upsertProbe(
            origin = searchBook.origin,
            chapterKey = chapterKey,
            status = ChangeSourceChapterProbe.STATUS_CONTENT_FAIL,
            score = 0.0,
        )
        searchBook.chapterWordCountText = msg
        searchBook.chapterWordCount = -1
    }

    private suspend fun ensureToc(
        book: Book,
        origin: String,
        hostBucket: CheckHostTokenBucket,
    ): List<BookChapter>? {
        tocMap[book.primaryStr()]?.let { return it }
        val host = AskSourcePrefetch.hostOf(origin)
        if (host.isNotEmpty()) hostBucket.acquire(host)
        val source = appDb.bookSourceDao.getBookSource(origin) ?: return null
        return withTimeout(AskTimeout.timeoutMs(source.respondTime)) {
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

    fun getContent(
        book: Book,
        chapter: BookChapter,
        nextChapterUrl: String?,
        success: (content: String) -> Unit,
        error: (msg: String) -> Unit
    ) {
        execute {
            val bookSource = appDb.bookSourceDao.getBookSource(book.origin)
                ?: throw NoStackTraceException("书源不存在")
            WebBook.getContentAwait(bookSource, book, chapter, nextChapterUrl, false)
        }.onSuccess {
            success.invoke(it)
        }.onError {
            error.invoke(it.localizedMessage ?: "获取正文出错")
        }
    }

}
