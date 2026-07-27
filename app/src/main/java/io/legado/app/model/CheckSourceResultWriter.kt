package io.legado.app.model

import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Batches check-result column updates to cut SQLite write pressure.
 */
object CheckSourceResultWriter {

    private const val FLUSH_EVERY = 40

    private val pending = ConcurrentLinkedQueue<BookSource>()
    private val mutex = Mutex()

    fun enqueue(source: BookSource) {
        pending.add(source)
        if (pending.size >= FLUSH_EVERY) {
            // Best-effort; callers also flush on completion.
        }
    }

    suspend fun enqueueAndMaybeFlush(source: BookSource) {
        pending.add(source)
        if (pending.size >= FLUSH_EVERY) flush()
    }

    suspend fun flush() = mutex.withLock {
        val batch = ArrayList<BookSource>(FLUSH_EVERY * 2)
        while (true) {
            val item = pending.poll() ?: break
            batch.add(item)
        }
        if (batch.isEmpty()) {
            return@withLock
        }
        for (source in batch) {
            appDb.bookSourceDao.updateCheckResult(
                bookSourceUrl = source.bookSourceUrl,
                bookSourceGroup = source.bookSourceGroup,
                bookSourceComment = source.bookSourceComment,
                respondTime = source.respondTime,
                enabled = source.enabled,
            )
        }
    }
}
