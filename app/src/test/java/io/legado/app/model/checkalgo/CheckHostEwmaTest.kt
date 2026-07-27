package io.legado.app.model.checkalgo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CheckHostEwmaTest {

    @Test
    fun `unknown host uses default prior`() {
        val ewma = CheckHostEwma(prior = 0.7)
        assertEquals(0.7, ewma.successRate("unknown.example"), 1e-9)
        assertTrue(ewma.shouldDeepCheck("unknown.example"))
    }

    @Test
    fun `failure decreases ewma from prior`() {
        val alpha = 0.2
        val prior = 0.7
        val ewma = CheckHostEwma(alpha = alpha, prior = prior)
        ewma.onResult("b.example", success = false)
        val expected = alpha * 0.0 + (1.0 - alpha) * prior
        assertEquals(expected, ewma.successRate("b.example"), 1e-9)
    }

    @Test
    fun `repeated failures eventually skip deep check`() {
        val ewma = CheckHostEwma(alpha = 0.5, prior = 0.7)
        val host = "bad.example"
        repeat(6) { ewma.onResult(host, success = false) }
        assertTrue(ewma.successRate(host) < 0.35)
        assertFalse(ewma.shouldDeepCheck(host))
    }

    @Test
    fun `shouldDeepCheck respects custom min rate`() {
        val ewma = CheckHostEwma(alpha = 0.2, prior = 0.7)
        ewma.onResult("c.example", success = false)
        val rate = ewma.successRate("c.example")
        assertFalse(ewma.shouldDeepCheck("c.example", minRate = rate + 0.01))
        assertTrue(ewma.shouldDeepCheck("c.example", minRate = rate - 0.01))
    }

    @Test
    fun `onResult is thread safe per host`() {
        val ewma = CheckHostEwma()
        val host = "concurrent.example"
        val threads = 16
        val iterations = 200
        val pool = Executors.newFixedThreadPool(threads)
        val latch = CountDownLatch(threads)
        try {
            repeat(threads) {
                pool.execute {
                    repeat(iterations) { i ->
                        ewma.onResult(host, success = i % 2 == 0)
                    }
                    latch.countDown()
                }
            }
            assertTrue(latch.await(10, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
        val rate = ewma.successRate(host)
        assertTrue(rate in 0.0..1.0)
        assertFalse(rate.isNaN())
    }

    @Test
    fun `success after failures can recover above deep check threshold`() {
        val ewma = CheckHostEwma(alpha = 0.5, prior = 0.7)
        val host = "recover.example"
        repeat(6) { ewma.onResult(host, success = false) }
        assertFalse(ewma.shouldDeepCheck(host))
        repeat(8) { ewma.onResult(host, success = true) }
        assertTrue(ewma.successRate(host) >= 0.35)
        assertTrue(ewma.shouldDeepCheck(host))
    }
}
