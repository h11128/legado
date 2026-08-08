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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEmpty
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.withTimeout

/**
 * Shared auto-换源 ask path for read / manga (precise search + toc + content).
 *
 * Callers own UI msgs, prefs gate, session once-per-book, and [changeTo].
 */
object AutoChangeSource {

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

    /**
     * First source that precise-matches [name]/[author] and yields toc+content.
     * Throws [NoStackTraceException] when none succeed.
     */
    suspend fun findFirst(
        name: String,
        author: String,
        excludeOrigin: String? = null,
        threadCount: Int = AppConfig.threadCount,
        timeoutMs: Long = AskTimeout.AUTO_CHANGE_MS,
        onStart: (() -> Unit)? = null,
        onCompletion: (() -> Unit)? = null,
    ): Triple<Book, List<BookChapter>, BookSource> {
        SourceHelp.ensureRespondTimeHealed()
        val ordered = AskSourceOrder.order(
            filterParts(appDb.bookSourceDao.allTextEnabledPart, excludeOrigin),
            threadCount = threadCount,
            demoteUrls = ChangeSourceAskMemory.snapshot(),
        )
        ChangeSourceLog.i(
            "auto-change ask name=$name author=$author exclude=${excludeOrigin.orEmpty()} " +
                "candidates=${ordered.size} threads=$threadCount"
        )
        return AskSourcePrefetch.emitSources(ordered)
            .onStart { onStart?.invoke() }
            .mapParallelSafe(threadCount) { source ->
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
            }
            .take(1)
            .onEmpty { throw NoStackTraceException("没有合适书源") }
            .onCompletion { onCompletion?.invoke() }
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
