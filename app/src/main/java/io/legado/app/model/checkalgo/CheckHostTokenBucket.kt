package io.legado.app.model.checkalgo

import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * Per-host token bucket for bulk check outbound pressure.
 *
 * When a book source sets [concurrentRate] (`n/ms` or interval ms), acquire uses the
 * tighter of that rate and the bucket defaults so sensitive hosts are not blasted
 * at the global 4 QPS while AnalyzeUrl still applies per-request limiting.
 */
class CheckHostTokenBucket(
    private val maxTokensPerHost: Int = DEFAULT_MAX_TOKENS,
    private val refillPerSecond: Double = DEFAULT_REFILL_PER_SECOND,
) {
    private data class Bucket(
        var tokens: Double,
        var lastNanos: Long,
        var maxTokens: Double,
        var refillPerSecond: Double,
    )

    private val buckets = ConcurrentHashMap<String, Bucket>()

    fun tryAcquire(host: String): Boolean =
        tryAcquire(host, maxTokensPerHost, refillPerSecond)

    fun tryAcquire(
        host: String,
        concurrentRate: String?,
    ): Boolean {
        val (maxTokens, refill) = limitsForConcurrentRate(
            concurrentRate,
            maxTokensPerHost,
            refillPerSecond,
        )
        return tryAcquire(host, maxTokens, refill)
    }

    fun tryAcquire(host: String, maxTokens: Int, refill: Double): Boolean {
        val b = bucket(host, maxTokens, refill)
        return synchronized(b) {
            tighten(b, maxTokens, refill)
            refillBucket(b)
            if (b.tokens < 1.0) return false
            b.tokens -= 1.0
            true
        }
    }

    suspend fun acquire(host: String) {
        acquire(host, concurrentRate = null)
    }

    suspend fun acquire(host: String, concurrentRate: String?) {
        val (maxTokens, refill) = limitsForConcurrentRate(
            concurrentRate,
            maxTokensPerHost,
            refillPerSecond,
        )
        while (true) {
            if (tryAcquire(host, maxTokens, refill)) return
            delay(25)
        }
    }

    private fun bucket(host: String, maxTokens: Int, refill: Double): Bucket {
        return buckets.getOrPut(host) {
            Bucket(
                tokens = maxTokens.toDouble(),
                lastNanos = System.nanoTime(),
                maxTokens = maxTokens.toDouble(),
                refillPerSecond = refill,
            )
        }
    }

    private fun tighten(b: Bucket, maxTokens: Int, refill: Double) {
        if (maxTokens.toDouble() < b.maxTokens) {
            b.maxTokens = maxTokens.toDouble()
            if (b.tokens > b.maxTokens) b.tokens = b.maxTokens
        }
        if (refill < b.refillPerSecond) {
            b.refillPerSecond = refill
        }
    }

    private fun refillBucket(b: Bucket) {
        val now = System.nanoTime()
        val elapsed = (now - b.lastNanos).coerceAtLeast(0L) / 1_000_000_000.0
        if (elapsed <= 0) return
        b.tokens = min(b.maxTokens, b.tokens + elapsed * b.refillPerSecond)
        b.lastNanos = now
    }

    /** Hosts with available tokens below [threshold] (for MCP progress snapshot). */
    fun hostsWithLowTokens(threshold: Double = 1.0): Map<String, Double> {
        return buckets.mapValues { (_, b) ->
            synchronized(b) {
                refillBucket(b)
                b.tokens
            }
        }.filterValues { it < threshold }
    }

    companion object {
        const val DEFAULT_MAX_TOKENS = 4
        const val DEFAULT_REFILL_PER_SECOND = 4.0

        /**
         * Map bookSource `concurrentRate` to (maxTokens, refillPerSecond).
         * Never looser than [defaultMax]/[defaultRefill].
         *
         * Formats match [io.legado.app.help.ConcurrentRateLimiter]:
         * - `n/ms` → n accesses per ms window
         * - `ms` alone → 1 access per ms window
         */
        fun limitsForConcurrentRate(
            concurrentRate: String?,
            defaultMax: Int = DEFAULT_MAX_TOKENS,
            defaultRefill: Double = DEFAULT_REFILL_PER_SECOND,
        ): Pair<Int, Double> {
            if (concurrentRate.isNullOrBlank() || concurrentRate == "0") {
                return defaultMax to defaultRefill
            }
            return try {
                val rateIndex = concurrentRate.indexOf('/')
                val accessLimit: Int
                val intervalMs: Int
                if (rateIndex > 0) {
                    accessLimit = concurrentRate.take(rateIndex).toInt()
                    intervalMs = concurrentRate.substring(rateIndex + 1).toInt()
                } else {
                    accessLimit = 1
                    intervalMs = concurrentRate.toInt()
                }
                if (accessLimit <= 0 || intervalMs <= 0) {
                    return defaultMax to defaultRefill
                }
                val qps = accessLimit * 1000.0 / intervalMs.toDouble()
                val maxTokens = accessLimit.coerceIn(1, defaultMax)
                // Never looser than defaults; do not floor QPS upward (honor strict rates).
                val refill = qps.coerceAtMost(defaultRefill).coerceAtLeast(0.0)
                maxTokens to refill
            } catch (_: NumberFormatException) {
                defaultMax to defaultRefill
            }
        }
    }
}
