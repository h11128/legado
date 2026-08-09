package io.legado.app.model.review

/**
 * RFC-004 §6.5.1 — paragraph-icon authority gate (per provider source).
 * Default [Kind.Unsupported] keeps P1 chapter-bucket only.
 */
object ReviewParagraphAuthority {

    const val FIXTURE_PROVIDER_URL = "legado-fixture://review-overlay"

    enum class Kind {
        Unsupported,
        ContentSplitVerified,
        RuleEmittedPreview,
    }

    fun authorityFor(providerSourceUrl: String): Kind = when (providerSourceUrl) {
        FIXTURE_PROVIDER_URL -> Kind.ContentSplitVerified
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
