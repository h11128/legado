package io.legado.app.model.checkalgo

import kotlin.math.min

/**
 * Pure early-stop predicate for ask-path search / 换源.
 *
 * Stops when enabled and either enough results or a positive time budget elapsed,
 * and at least one full wave of workers has completed.
 * Time-budget stop does not require matches (intentional wall-clock cap).
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
