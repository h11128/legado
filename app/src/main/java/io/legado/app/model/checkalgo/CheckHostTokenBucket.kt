package io.legado.app.model.checkalgo

import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * Per-host token bucket for bulk check outbound pressure.
 */
class CheckHostTokenBucket(
    private val maxTokensPerHost: Int = 4,
    private val refillPerSecond: Double = 4.0,
) {
    private data class Bucket(
        var tokens: Double,
        var lastNanos: Long,
    )

    private val buckets = ConcurrentHashMap<String, Bucket>()

    fun tryAcquire(host: String): Boolean {
        val b = bucket(host)
        return synchronized(b) {
            refill(b)
            if (b.tokens < 1.0) return false
            b.tokens -= 1.0
            true
        }
    }

    suspend fun acquire(host: String) {
        while (true) {
            if (tryAcquire(host)) return
            delay(25)
        }
    }

    private fun bucket(host: String): Bucket {
        return buckets.getOrPut(host) {
            Bucket(tokens = maxTokensPerHost.toDouble(), lastNanos = System.nanoTime())
        }
    }

    private fun refill(b: Bucket) {
        val now = System.nanoTime()
        val elapsed = (now - b.lastNanos).coerceAtLeast(0L) / 1_000_000_000.0
        if (elapsed <= 0) return
        b.tokens = min(maxTokensPerHost.toDouble(), b.tokens + elapsed * refillPerSecond)
        b.lastNanos = now
    }

    /** Hosts with available tokens below [threshold] (for MCP progress snapshot). */
    fun hostsWithLowTokens(threshold: Double = 1.0): Map<String, Double> {
        return buckets.mapValues { (_, b) ->
            synchronized(b) {
                refill(b)
                b.tokens
            }
        }.filterValues { it < threshold }
    }
}
