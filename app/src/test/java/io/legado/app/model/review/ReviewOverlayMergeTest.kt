package io.legado.app.model.review

import io.legado.app.data.entities.BookReviewBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewOverlayMergeTest {

    private fun row(
        provider: String,
        sortOrder: Int,
        enabled: Boolean = true,
        role: String = BookReviewBinding.ROLE_CHAPTER,
        id: Long = sortOrder.toLong(),
    ) = BookReviewBinding(
        id = id,
        contentBookUrl = "https://content/book/1",
        providerSourceUrl = provider,
        providerBookUrl = "$provider/book",
        enabled = enabled,
        sortOrder = sortOrder,
        role = role,
    )

    @Test
    fun selectBindingsRespectsMergeOff() {
        val all = listOf(row("a", 0), row("b", 1), row("c", 2))
        val selected = ReviewOverlayMerge.selectBindings(all, mergeEnabled = false, mergeMax = 5)
        assertEquals(listOf("a"), selected.map { it.providerSourceUrl })
    }

    @Test
    fun selectBindingsCapsAndSkipsDisabled() {
        val all = listOf(
            row("a", 2, enabled = false),
            row("b", 0),
            row("c", 1),
            row("d", 3),
            row("e", 4),
            row("f", 5),
        )
        val selected = ReviewOverlayMerge.selectBindings(all, mergeEnabled = true, mergeMax = 3)
        assertEquals(listOf("b", "c", "d"), selected.map { it.providerSourceUrl })
    }

    @Test
    fun selectBindingsSortsBySortOrderThenId() {
        val all = listOf(
            row("late", 1, id = 20),
            row("early", 1, id = 5),
            row("first", 0, id = 99),
        )
        val selected = ReviewOverlayMerge.selectBindings(all, mergeEnabled = true, mergeMax = 5)
        assertEquals(listOf("first", "early", "late"), selected.map { it.providerSourceUrl })
    }

    @Test
    fun sumChapterBucketCountIgnoresNegativesAsZero() {
        assertEquals(0, ReviewOverlayMerge.sumChapterBucketCount(emptyList()))
        assertEquals(12, ReviewOverlayMerge.sumChapterBucketCount(listOf(5, 7)))
        assertEquals(5, ReviewOverlayMerge.sumChapterBucketCount(listOf(5, -3, 0)))
    }

    @Test
    fun paragraphPrimaryPrefersRoleThenFirstEnabled() {
        val rows = listOf(
            row("a", 0),
            row("b", 1, role = BookReviewBinding.ROLE_PARAGRAPH_PRIMARY),
            row("c", 2),
        )
        assertEquals("b", ReviewOverlayMerge.paragraphPrimary(rows)?.providerSourceUrl)
        assertEquals(
            "a",
            ReviewOverlayMerge.paragraphPrimary(listOf(row("a", 0), row("c", 1)))?.providerSourceUrl,
        )
        assertNull(ReviewOverlayMerge.paragraphPrimary(listOf(row("x", 0, enabled = false))))
        assertTrue(
            ReviewOverlayMerge.paragraphPrimary(
                listOf(row("x", 0, enabled = false), row("y", 1)),
            )?.providerSourceUrl == "y"
        )
    }
}
