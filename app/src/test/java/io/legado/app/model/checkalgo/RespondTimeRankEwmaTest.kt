package io.legado.app.model.checkalgo

import io.legado.app.data.entities.BookSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RespondTimeRankEwmaTest {

    @Test
    fun blendsWhenPreviousIsSuccess() {
        val previous = 1_000L
        val sample = 4_000L
        val alpha = 0.3
        val expectedBlend = (alpha * sample + (1.0 - alpha) * previous).toLong()
        val result = RespondTimeRank.ewmaSuccess(previous, sample, alpha)
        assertEquals(RespondTimeRank.encodeSuccess(expectedBlend), result)
        assertEquals(RespondTimeRank.SUCCESS, RespondTimeRank.classify(result))
        assertTrue(result < BookSource.DEFAULT_RESPOND_TIME)
    }

    @Test
    fun startsFreshFromSampleWhenPreviousUnknown() {
        val result = RespondTimeRank.ewmaSuccess(
            previousRespondTime = BookSource.DEFAULT_RESPOND_TIME,
            sampleElapsedMs = 2_500,
        )
        assertEquals(2_500L, result)
    }

    @Test
    fun startsFreshFromSampleWhenPreviousFailure() {
        val result = RespondTimeRank.ewmaSuccess(
            previousRespondTime = BookSource.DEFAULT_RESPOND_TIME + 10,
            sampleElapsedMs = 800,
        )
        assertEquals(800L, result)
    }

    @Test
    fun neverReturnsAtOrAboveDefault() {
        val huge = BookSource.DEFAULT_RESPOND_TIME * 10
        val fromUnknown = RespondTimeRank.ewmaSuccess(
            BookSource.DEFAULT_RESPOND_TIME,
            huge,
        )
        assertTrue(fromUnknown < BookSource.DEFAULT_RESPOND_TIME)
        assertEquals(BookSource.DEFAULT_RESPOND_TIME - 1, fromUnknown)

        val fromSuccess = RespondTimeRank.ewmaSuccess(
            BookSource.DEFAULT_RESPOND_TIME - 2,
            huge,
        )
        assertTrue(fromSuccess < BookSource.DEFAULT_RESPOND_TIME)
    }

    @Test
    fun defaultAlphaIsPointThree() {
        assertEquals(0.3, RespondTimeRank.EWMA_ALPHA, 0.0)
        val previous = 2_000L
        val sample = 5_000L
        val withDefault = RespondTimeRank.ewmaSuccess(previous, sample)
        val withExplicit = RespondTimeRank.ewmaSuccess(previous, sample, 0.3)
        assertEquals(withExplicit, withDefault)
    }
}
