package io.legado.app.model.checkalgo

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
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
}
