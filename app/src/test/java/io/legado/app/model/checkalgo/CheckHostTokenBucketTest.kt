package io.legado.app.model.checkalgo

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckHostTokenBucketTest {

    @Test
    fun `tryAcquire respects per-host capacity`() {
        val bucket = CheckHostTokenBucket(maxTokensPerHost = 2, refillPerSecond = 0.0)
        val host = "a.example"
        assertTrue(bucket.tryAcquire(host))
        assertTrue(bucket.tryAcquire(host))
        assertFalse(bucket.tryAcquire(host))
        assertTrue(bucket.tryAcquire("b.example"))
    }

    @Test
    fun `acquire waits until token refills`() = runBlocking {
        val bucket = CheckHostTokenBucket(maxTokensPerHost = 1, refillPerSecond = 50.0)
        val host = "wait.example"
        assertTrue(bucket.tryAcquire(host))
        val results = (1..3).map {
            async { bucket.acquire(host); true }
        }.awaitAll()
        assertTrue(results.all { it })
    }

    @Test
    fun `hosts are limited independently`() {
        val bucket = CheckHostTokenBucket(maxTokensPerHost = 1, refillPerSecond = 0.0)
        assertTrue(bucket.tryAcquire("one.example"))
        assertTrue(bucket.tryAcquire("two.example"))
        assertFalse(bucket.tryAcquire("one.example"))
        assertFalse(bucket.tryAcquire("two.example"))
    }

    @Test
    fun `limitsForConcurrentRate parses n-per-ms and interval forms`() {
        assertEquals(
            4 to 4.0,
            CheckHostTokenBucket.limitsForConcurrentRate(null),
        )
        assertEquals(
            4 to 4.0,
            CheckHostTokenBucket.limitsForConcurrentRate("0"),
        )
        // 1 access / 1000ms → 1 QPS, maxTokens=1
        assertEquals(
            1 to 1.0,
            CheckHostTokenBucket.limitsForConcurrentRate("1/1000"),
        )
        // bare interval ms → 1 access per window (1/2000 → 0.5 QPS)
        assertEquals(
            1 to 0.5,
            CheckHostTokenBucket.limitsForConcurrentRate("2000"),
        )
        // strict rate must not be floored upward (1/60000 → ~0.0167 QPS)
        val strict = CheckHostTokenBucket.limitsForConcurrentRate("1/60000")
        assertEquals(1, strict.first)
        assertEquals(1.0 / 60.0, strict.second, 1e-9)
        // never looser than defaults (10/1000 = 10 QPS → clamped to 4)
        assertEquals(
            4 to 4.0,
            CheckHostTokenBucket.limitsForConcurrentRate("10/1000"),
        )
    }

    @Test
    fun `source concurrentRate tightens host bucket`() {
        val bucket = CheckHostTokenBucket(maxTokensPerHost = 4, refillPerSecond = 4.0)
        val host = "tight.example"
        assertTrue(bucket.tryAcquire(host, "1/1000"))
        // After one acquire at 1 token max, host is exhausted until refill
        assertFalse(bucket.tryAcquire(host, "1/1000"))
        assertFalse(bucket.tryAcquire(host))
    }
}
