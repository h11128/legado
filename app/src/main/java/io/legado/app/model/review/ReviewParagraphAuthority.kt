package io.legado.app.model.review

/**
 * RFC-004 §6.5.1 — paragraph-icon authority gate (per provider source).
 * Default [Kind.Unsupported] keeps P1 chapter-bucket only.
 */
object ReviewParagraphAuthority {

    const val FIXTURE_PROVIDER_URL = "legado-fixture://review-overlay"
    /** Device-verified 2026-08-09: getContent <p> split order == chapterReview paragraphId 1..N. */
    const val QIDIAN_REVIEW_PROVIDER_URL = "https://m.qidian.com#rfc004-review"

    enum class Kind {
        Unsupported,
        ContentSplitVerified,
        RuleEmittedPreview,
    }

    fun authorityFor(providerSourceUrl: String): Kind = when (providerSourceUrl) {
        FIXTURE_PROVIDER_URL,
        QIDIAN_REVIEW_PROVIDER_URL,
        -> Kind.ContentSplitVerified
        else -> Kind.Unsupported
    }

    /**
     * Only [Kind.ContentSplitVerified] opens hard-map. [Kind.RuleEmittedPreview]
     * needs a separate path when rule-emitted previews ship — do not treat as open.
     */
    fun isParagraphMapOpen(kind: Kind): Boolean = kind == Kind.ContentSplitVerified

    fun isParagraphMapOpen(providerSourceUrl: String): Boolean =
        isParagraphMapOpen(authorityFor(providerSourceUrl))
}
