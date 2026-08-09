package io.legado.app.model.review

import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.ReviewRule

/**
 * RFC-004 §6.1 — detect review-capable sources without executing JS.
 */
object ReviewCapability {

    private val summaryFn = Regex(
        """(?:function\s+getReviewSummary\b)|(?:\bgetReviewSummary\s*=)""",
    )
    private val detailFn = Regex(
        """(?:function\s+getReviewDetail\b)|(?:\bgetReviewDetail\s*=)""",
    )

    fun isReviewCapable(source: BookSource): Boolean {
        if (source.isJsSource()) {
            return declaresJsReviewPair(source.mainJs)
        }
        return isNativeReviewCapable(source.ruleReview)
    }

    fun declaresJsReviewPair(mainJs: String?): Boolean {
        val text = mainJs?.takeIf { it.isNotBlank() } ?: return false
        return summaryFn.containsMatchIn(text) && detailFn.containsMatchIn(text)
    }

    fun isNativeReviewCapable(rule: ReviewRule?): Boolean {
        rule ?: return false
        if (!rule.enabled) return false
        return !rule.reviewSummaryUrl.isNullOrBlank()
                && !rule.summaryListRule.isNullOrBlank()
                && !rule.summaryParagraphIndexRule.isNullOrBlank()
                && !rule.summaryCountRule.isNullOrBlank()
                && !rule.reviewDetailUrl.isNullOrBlank()
                && !rule.detailListRule.isNullOrBlank()
                && !rule.detailContentRule.isNullOrBlank()
    }

    fun capableCount(sources: Iterable<BookSource>): Int =
        sources.count { isReviewCapable(it) }
}
