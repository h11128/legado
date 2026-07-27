package io.legado.app.model.checkalgo

import org.junit.Assert.assertEquals
import org.junit.Test

class CheckAimdLimiterTest {

    @Test
    fun startInitializesAndCapsAtMax() {
        val limiter = CheckAimdLimiter(maxConcurrency = 8, minConcurrency = 2, initial = 2)
        assertEquals(2, limiter.current())
        repeat(20) { limiter.onSuccess() }
        assertEquals(8, limiter.current())
    }

    @Test
    fun onSuccessIncreasesAdditively() {
        val limiter = CheckAimdLimiter(maxConcurrency = 5, minConcurrency = 1, initial = 1)
        limiter.onSuccess()
        assertEquals(2, limiter.current())
        limiter.onSuccess()
        assertEquals(3, limiter.current())
    }

    @Test
    fun onTimeoutHalvesDownToMin() {
        val limiter = CheckAimdLimiter(maxConcurrency = 16, minConcurrency = 2, initial = 11)
        assertEquals(11, limiter.current())
        limiter.onTimeout()
        assertEquals(5, limiter.current())
        limiter.onTimeout()
        assertEquals(2, limiter.current())
        limiter.onTimeout()
        assertEquals(2, limiter.current())
    }

    @Test
    fun onHardFailHalvesLikeTimeout() {
        val limiter = CheckAimdLimiter(maxConcurrency = 10, minConcurrency = 1, initial = 7)
        limiter.onHardFail()
        assertEquals(3, limiter.current())
    }

    @Test
    fun onSlowDecreasesMildlyWhenWellOverSoftLimit() {
        val limiter = CheckAimdLimiter(maxConcurrency = 8, minConcurrency = 1, initial = 5)
        limiter.onSlow(durationMs = 3000, softLimitMs = 1000)
        assertEquals(4, limiter.current())
        limiter.onSlow(durationMs = 500, softLimitMs = 1000)
        assertEquals(4, limiter.current())
    }
}
