package io.legado.app.model.checkalgo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AskEarlyStopTest {

    @Test
    fun disabledNeverStops() {
        assertFalse(
            AskEarlyStop.shouldStop(
                enabled = false,
                resultCount = 100,
                resultThreshold = 1,
                completedCount = 32,
                totalSources = 100,
                threadCount = 32,
                elapsedMs = 60_000,
                budgetMs = 1_000,
            ),
        )
    }

    @Test
    fun stopsWhenResultsHitThresholdAndWaveDone() {
        assertTrue(
            AskEarlyStop.shouldStop(
                enabled = true,
                resultCount = 5,
                resultThreshold = 5,
                completedCount = 8,
                totalSources = 100,
                threadCount = 8,
                elapsedMs = 100,
                budgetMs = 0,
            ),
        )
    }

    @Test
    fun stopsWhenBudgetElapsedAndWaveDone() {
        assertTrue(
            AskEarlyStop.shouldStop(
                enabled = true,
                resultCount = 0,
                resultThreshold = 10,
                completedCount = 4,
                totalSources = 4,
                threadCount = 32,
                elapsedMs = 5_000,
                budgetMs = 5_000,
            ),
        )
    }

    @Test
    fun zeroBudgetIgnoresTime() {
        assertFalse(
            AskEarlyStop.shouldStop(
                enabled = true,
                resultCount = 0,
                resultThreshold = 10,
                completedCount = 32,
                totalSources = 100,
                threadCount = 32,
                elapsedMs = 999_999,
                budgetMs = 0,
            ),
        )
    }

    @Test
    fun doesNotStopBeforeFirstWaveCompletes() {
        assertFalse(
            AskEarlyStop.shouldStop(
                enabled = true,
                resultCount = 50,
                resultThreshold = 1,
                completedCount = 3,
                totalSources = 100,
                threadCount = 8,
                elapsedMs = 10_000,
                budgetMs = 1_000,
            ),
        )
    }

    @Test
    fun waveCapUsesTotalWhenSmallerThanThreads() {
        assertTrue(
            AskEarlyStop.shouldStop(
                enabled = true,
                resultCount = 1,
                resultThreshold = 1,
                completedCount = 3,
                totalSources = 3,
                threadCount = 32,
                elapsedMs = 0,
                budgetMs = 0,
            ),
        )
        assertFalse(
            AskEarlyStop.shouldStop(
                enabled = true,
                resultCount = 1,
                resultThreshold = 1,
                completedCount = 2,
                totalSources = 3,
                threadCount = 32,
                elapsedMs = 0,
                budgetMs = 0,
            ),
        )
    }
}
