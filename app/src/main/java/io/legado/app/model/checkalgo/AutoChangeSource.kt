package io.legado.app.model.checkalgo

import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.config.AppConfig
import io.legado.app.help.source.SourceHelp
import io.legado.app.model.RespondTimeUpdater
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.mapParallelSafe
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlin.math.min
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEmpty
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout

/**
 * Shared auto-换源 ask path for read / manga (precise search + toc + content).
 *
 * Callers own UI overlay, prefs gate, session once-per-book, and [changeTo].
 * Candidate list is hard-capped — this is not a full-catalog scan.
 */
object AutoChangeSource {

    /** Max enabled sources to ask on one auto-换源 attempt. */
    const val CANDIDATE_CAP = 30

    /** Match manual 换源 publish throttle (~12.5fps). */
    private const val PROGRESS_THROTTLE_MS = 80L

    enum class Trigger {
        MISSING_SOURCE,
        INFO_FAIL,
        TOC_FAIL,
    }

    /** Drop the dead/current origin so we do not re-pick the same broken source. */
    fun filterParts(
        parts: List<BookSourcePart>,
        excludeOrigin: String?,
    ): List<BookSourcePart> {
        val exclude = excludeOrigin?.trim().orEmpty()
        if (exclude.isEmpty()) return parts
        return parts.filter { it.bookSourceUrl != exclude }
    }

    /** Keep ask-order head only; auto path must not scan the whole catalog. */
    fun limitCandidates(
        parts: List<BookSourcePart>,
        cap: Int = CANDIDATE_CAP,
    ): List<BookSourcePart> {
        if (cap <= 0 || parts.size <= cap) return parts
        return parts.take(cap)
    }

    /**
     * First source that precise-matches [name]/[author] and yields toc+content.
     * Throws [NoStackTraceException] when none succeed.
     *
     * [onProgress] reports live ask strip (completed / inFlight / probing names).
     */
    suspend fun findFirst(
        name: String,
        author: String,
        excludeOrigin: String? = null,
        threadCount: Int = AppConfig.threadCount,
        timeoutMs: Long = AskTimeout.AUTO_CHANGE_MS,
        onStart: (() -> Unit)? = null,
        onProgress: ((AutoChangeProgressUi) -> Unit)? = null,
        onCompletion: (() -> Unit)? = null,
    ): Triple<Book, List<BookChapter>, BookSource> {
        SourceHelp.ensureRespondTimeHealed()
        val ordered = limitCandidates(
            AskSourceOrder.order(
                filterParts(appDb.bookSourceDao.allTextEnabledPart, excludeOrigin),
                threadCount = threadCount,
                demoteUrls = ChangeSourceAskMemory.snapshot(),
            ),
        )
        val total = ordered.size
        val done = AtomicInteger(0)
        val concurrency = min(threadCount.coerceAtLeast(1), total.coerceAtLeast(1))
        val probingByUrl = ConcurrentHashMap<String, String>()
        val lastPublishMs = AtomicLong(0L)
        ChangeSourceLog.i(
            "auto-change ask name=$name author=$author exclude=${excludeOrigin.orEmpty()} " +
                "candidates=$total cap=$CANDIDATE_CAP threads=$concurrency"
        )

        fun publish(force: Boolean = false, finished: Boolean = false) {
            if (onProgress == null) return
            val now = System.currentTimeMillis()
            if (!force && !finished) {
                val prev = lastPublishMs.get()
                if (now - prev < PROGRESS_THROTTLE_MS) return
                if (!lastPublishMs.compareAndSet(prev, now)) return
            } else {
                lastPublishMs.set(now)
            }
            val names = probingByUrl.values.toList()
            val sample = names.take(3).joinToString("、")
            val more = (names.size - 3).coerceAtLeast(0)
            val label = when {
                names.isEmpty() -> ""
                more > 0 -> "询问中 $sample 等${more}个"
                else -> "询问中 $sample"
            }
            onProgress(
                AutoChangeProgressUi(
                    completed = done.get(),
                    total = total,
                    inFlight = probingByUrl.size,
                    concurrency = concurrency,
                    label = label,
                    finished = finished,
                )
            )
        }

        publish(force = true)
        return AskSourcePrefetch.emitSources(ordered)
            .onStart { onStart?.invoke() }
            .mapParallelSafe(concurrency) { source ->
                val displayName = source.bookSourceName.ifBlank { source.bookSourceUrl }
                probingByUrl[source.bookSourceUrl] = displayName
                publish()
                try {
                    val startTime = System.currentTimeMillis()
                    withTimeout(timeoutMs) {
                        val book = WebBook.preciseSearchAwait(source, name, author).getOrThrow()
                        if (book.tocUrl.isEmpty()) {
                            WebBook.getBookInfoAwait(source, book)
                        }
                        val toc = WebBook.getChapterListAwait(source, book).getOrThrow()
                        val chapter = toc.getOrElse(book.durChapterIndex) { toc.last() }
                        val nextChapterUrl = toc.getOrNull(chapter.index + 1)?.url
                        WebBook.getContentAwait(
                            bookSource = source,
                            book = book,
                            bookChapter = chapter,
                            nextChapterUrl = nextChapterUrl
                        )
                        Triple(
                            book,
                            toc,
                            System.currentTimeMillis() - startTime to source,
                        )
                    }
                } finally {
                    probingByUrl.remove(source.bookSourceUrl)
                    // Count finished asks only while still active; cancelled siblings
                    // still refresh inFlight so the strip does not freeze on old names.
                    if (coroutineContext.isActive) {
                        done.incrementAndGet()
                        publish(force = true)
                    } else {
                        publish()
                    }
                }
            }
            .take(1)
            .onEmpty { throw NoStackTraceException("没有合适书源") }
            .onCompletion {
                publish(force = true, finished = true)
                onCompletion?.invoke()
            }
            .first()
            .let { (book, toc, timing) ->
                val (elapsed, source) = timing
                RespondTimeUpdater.noteSuccess(
                    source.bookSourceUrl,
                    elapsed,
                    source.respondTime,
                )
                RespondTimeUpdater.flush()
                Triple(book, toc, source)
            }
    }

    fun logTrigger(trigger: Trigger, origin: String?, bookUrl: String) {
        ChangeSourceLog.i(
            "auto-change trigger=${trigger.name.lowercase()} origin=${origin.orEmpty()} " +
                "bookUrl=$bookUrl"
        )
    }
}
