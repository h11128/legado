package io.legado.app.model.checkalgo

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay

/**
 * Shared runtime for advanced bulk-check algorithms.
 */
object CheckAlgoRuntime {
    @Volatile
    var ewma: CheckHostEwma = CheckHostEwma()
        private set

    @Volatile
    var bloom: CheckUrlBloom = CheckUrlBloom(20_000)
        private set

    fun resetEwma() {
        ewma = CheckHostEwma()
    }

    fun resetBloom() {
        bloom = CheckUrlBloom(20_000)
    }

    fun resetSession() {
        resetEwma()
        resetBloom()
    }

    fun hostOf(url: String): String = url.toHttpUrlOrNull()?.host ?: url

    suspend fun acquireAimdSlot(aimd: CheckAimdLimiter, inFlight: AtomicInteger) {
        while (true) {
            val cur = inFlight.get()
            val limit = aimd.current()
            if (cur < limit && inFlight.compareAndSet(cur, cur + 1)) return
            delay(8)
        }
    }

    fun releaseAimdSlot(inFlight: AtomicInteger) {
        inFlight.updateAndGet { (it - 1).coerceAtLeast(0) }
    }
}
