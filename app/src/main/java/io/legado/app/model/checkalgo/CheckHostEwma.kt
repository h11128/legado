package io.legado.app.model.checkalgo

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-host EWMA success probability for skipping hopeless deep checks.
 */
class CheckHostEwma(
    private val alpha: Double = 0.2,
    private val prior: Double = 0.7,
) {
    private val rates = ConcurrentHashMap<String, Double>()

    fun onResult(host: String, success: Boolean) {
        val sample = if (success) 1.0 else 0.0
        rates.compute(host) { _, old ->
            val prev = old ?: prior
            (1 - alpha) * prev + alpha * sample
        }
    }

    fun successRate(host: String): Double = rates[host] ?: prior

    fun shouldDeepCheck(host: String, minRate: Double = 0.35): Boolean {
        return successRate(host) >= minRate
    }
}
