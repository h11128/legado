package io.legado.app.model.review

import io.legado.app.model.analyzeRule.ReviewRuleParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewOverlayLoaderTest {

    @Test
    fun chapterBucketOnlyKeepsMinusOne() {
        val raw = ReviewRuleParser.SummaryResult(
            counts = mapOf(-1 to 12, 1 to 3, 2 to 5),
            keys = mapOf(-1 to "章评", 1 to "p1", 2 to "p2"),
        )
        val filtered = ReviewOverlayLoader.chapterBucketOnly(raw)
        assertEquals(mapOf(-1 to 12), filtered.counts)
        assertEquals(mapOf(-1 to "章评"), filtered.keys)
    }

    @Test
    fun chapterBucketOnlyEmptyWhenNoMinusOne() {
        val raw = ReviewRuleParser.SummaryResult(
            counts = mapOf(1 to 3, 2 to 5),
            keys = mapOf(1 to "p1", 2 to "p2"),
        )
        val filtered = ReviewOverlayLoader.chapterBucketOnly(raw)
        assertTrue(filtered.counts.isEmpty())
        assertTrue(filtered.keys.isEmpty())
    }

    @Test
    fun chapterBucketOnlyDropsZeroCountMinusOne() {
        val raw = ReviewRuleParser.SummaryResult(
            counts = mapOf(-1 to 0, 1 to 2),
            keys = mapOf(-1 to "章评", 1 to "p1"),
        )
        val filtered = ReviewOverlayLoader.chapterBucketOnly(raw)
        assertTrue(filtered.counts.isEmpty())
        assertTrue(filtered.keys.isEmpty())
    }

    @Test
    fun chapterBucketOnlyAllowsMissingKeyInSummaryMap() {
        // Summary may omit keys; clickability requires paraData separately (bucketRef).
        val raw = ReviewRuleParser.SummaryResult(
            counts = mapOf(-1 to 4),
            keys = emptyMap(),
        )
        val filtered = ReviewOverlayLoader.chapterBucketOnly(raw)
        assertEquals(mapOf(-1 to 4), filtered.counts)
        assertTrue(filtered.keys.isEmpty())
    }

    @Test
    fun noFakeMinusOneWhenOnlyParagraphReviews() {
        val raw = ReviewRuleParser.SummaryResult(
            counts = mapOf(1 to 9),
            keys = mapOf(1 to "p1"),
        )
        assertNull(ReviewOverlayLoader.chapterBucketOnly(raw).counts[-1])
    }

    @Test
    fun mergedChapterChipUsesCountWithoutFakeParaDataKey() {
        val summary = ReviewOverlayLoader.mergeChapterDisplaySummary(
            sum = 12,
            paragraphPrimary = ReviewRuleParser.SummaryResult(
                counts = mapOf(-1 to 5, 1 to 2),
                keys = mapOf(-1 to "should-not-appear", 1 to "p1"),
            ),
        )
        assertEquals(12, summary.counts[-1])
        assertNull(summary.keys[-1])
        assertEquals("p1", summary.keys[1])
        assertEquals(2, summary.counts[1])
    }
}
