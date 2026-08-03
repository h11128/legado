package io.legado.app.model.checkalgo

import kotlin.math.min

/**
 * Pure early-stop predicate for ask-path search / 换源.
 *
 * Stops only when enabled, enough results (or time budget exhausted),
 * and at least one full wave of workers has completed.
 */
object AskEarlyStop {

    fun shouldStop(
        enabled: Boolean,
        resultCount: Int,
        resultThreshold: Int,
        completedCount: Int,
        totalSources: Int,
        threadCount: Int,
        elapsedMs: Long,
        budgetMs: Int, // 0 = ignore time
    ): Boolean {
        if (!enabled) return false
        val hitResultOrBudget =
            resultCount >= resultThreshold || (budgetMs > 0 && elapsedMs >= budgetMs)
        if (!hitResultOrBudget) return false
        return completedCount >= min(threadCount, totalSources)
    }
}
