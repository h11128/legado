package io.legado.app.model.review

import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.ReviewRule

/**
 * RFC-004 §6.1 — detect review-capable sources without executing JS.
 */
object ReviewCapability {

    /** URL fragment for dedicated cross-source review providers (not content 书源). */
    const val REVIEW_PROVIDER_URL_MARKER = "#rfc004-review"

    /** Device-acceptance fixtures — review-only, never content 换源. */
    const val REVIEW_FIXTURE_URL_PREFIX = "legado-fixture://"

    private val summaryFn = Regex(
        """(?:function\s+getReviewSummary\b)|(?:\bgetReviewSummary\s*=)""",
    )
    private val detailFn = Regex(
        """(?:function\s+getReviewDetail\b)|(?:\bgetReviewDetail\s*=)""",
    )

    /**
     * Dedicated 段评提供方 (RFC-004 `#rfc004-review` / fixture), not a normal
     * content book source. These must not appear in 换源 / content search pools.
     */
    fun isDedicatedReviewProvider(bookSourceUrl: String?): Boolean {
        val url = bookSourceUrl?.trim().orEmpty()
        if (url.isEmpty()) return false
        if (url.startsWith(REVIEW_FIXTURE_URL_PREFIX, ignoreCase = true)) return true
        return url.contains(REVIEW_PROVIDER_URL_MARKER, ignoreCase = true)
    }

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

    /** Drop dedicated 段评提供方 from content 换源 / auto-换源 candidate pools. */
    fun <T> excludeDedicatedReviewProviders(
        parts: List<T>,
        urlOf: (T) -> String,
    ): List<T> = parts.filterNot { isDedicatedReviewProvider(urlOf(it)) }
}
