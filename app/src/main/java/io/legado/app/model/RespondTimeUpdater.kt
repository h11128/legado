package io.legado.app.model

import io.legado.app.data.appDb
import io.legado.app.model.checkalgo.RespondTimeRank
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Batched respondTime-only updates for successful 换源/search probes (§6.4 RFC-001).
 * Gated on [Debug.isChecking] so concurrent check CAS writes are not starved.
 */
object RespondTimeUpdater {

    private const val FLUSH_EVERY = 40

    private val pending = ConcurrentLinkedQueue<Pair<String, Long>>()
    private val mutex = Mutex()

    fun noteSuccess(sourceUrl: String, elapsedMs: Long) {
        if (Debug.isChecking) return
        if (sourceUrl.isBlank()) return
        pending.add(sourceUrl to RespondTimeRank.encodeSuccess(elapsedMs))
    }

    suspend fun noteSuccessAndMaybeFlush(sourceUrl: String, elapsedMs: Long) {
        noteSuccess(sourceUrl, elapsedMs)
        if (!Debug.isChecking && pending.size >= FLUSH_EVERY) flush()
    }

    /**
     * Persist pending success writes. Skips entirely while a check session is active
     * (items stay queued; [Debug.finishChecking] schedules another flush).
     */
    suspend fun flush() = mutex.withLock {
        val batch = LinkedHashMap<String, Long>(FLUSH_EVERY * 2)
        while (true) {
            val item = pending.poll() ?: break
            batch[item.first] = item.second
        }
        if (batch.isEmpty()) return@withLock
        val applied = Debug.runIfNotChecking {
            appDb.runInTransaction {
                for ((url, respondTime) in batch) {
                    appDb.bookSourceDao.updateRespondTime(url, respondTime)
                }
            }
        }
        if (!applied) {
            for ((url, value) in batch) {
                pending.add(url to value)
            }
        }
    }
}
