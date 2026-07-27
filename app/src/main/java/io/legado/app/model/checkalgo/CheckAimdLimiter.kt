package io.legado.app.model.checkalgo

import java.util.concurrent.atomic.AtomicInteger

/**
 * AIMD adaptive concurrency: +1 on success, halve on timeout/hard fail.
 */
class CheckAimdLimiter(
    private val maxConcurrency: Int,
    private val minConcurrency: Int = 1,
    initial: Int = maxConcurrency,
) {
    private val current = AtomicInteger(initial.coerceIn(minConcurrency, maxConcurrency))

    fun current(): Int = current.get()

    fun onSuccess() {
        current.updateAndGet { cur -> (cur + 1).coerceAtMost(maxConcurrency) }
    }

    fun onTimeout() {
        current.updateAndGet { cur -> (cur / 2).coerceAtLeast(minConcurrency) }
    }

    fun onHardFail() = onTimeout()

    fun onSlow(durationMs: Long, softLimitMs: Long) {
        if (durationMs <= softLimitMs) return
        current.updateAndGet { cur -> (cur - 1).coerceAtLeast(minConcurrency) }
    }
}
