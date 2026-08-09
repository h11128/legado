package io.legado.app.model.review

import java.net.URI

/**
 * RFC-004 §6.5.1 — paragraph-icon authority gate (per provider source).
 * Default [Kind.Unsupported] keeps P1 chapter-bucket only.
 *
 * ContentSplitVerified alone is not enough for icons: provider body must share
 * host family with the shelf content origin (except offline fixture).
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

    /** Kind-only check (tests / call sites that already passed [allowsParagraphMapForContent]). */
    fun isParagraphMapOpen(providerSourceUrl: String): Boolean =
        isParagraphMapOpen(authorityFor(providerSourceUrl))

    /**
     * Full gate for drawing paragraph icons on a shelf book:
     * ContentSplitVerified **and** content origin host family matches the provider
     * (pirate body ↔ 起点评论 would otherwise hard-map with collapsed coverage / wrong icons).
     */
    fun allowsParagraphMapForContent(
        providerSourceUrl: String,
        contentOrigin: String?,
    ): Boolean {
        if (!isParagraphMapOpen(providerSourceUrl)) return false
        if (providerSourceUrl == FIXTURE_PROVIDER_URL) return true
        if (providerSourceUrl == QIDIAN_REVIEW_PROVIDER_URL) {
            return isQidianFamilyOrigin(contentOrigin)
        }
        return false
    }

    fun isQidianFamilyOrigin(originOrUrl: String?): Boolean {
        val host = hostOf(originOrUrl) ?: return false
        return host == "qidian.com" || host.endsWith(".qidian.com")
    }

    /** Registrable-ish host: strip www / m / book subdomains to last two labels when possible. */
    internal fun hostOf(originOrUrl: String?): String? {
        if (originOrUrl.isNullOrBlank()) return null
        val raw = originOrUrl.substringBefore('#').substringBefore('?').trim()
        val host = runCatching {
            when {
                raw.contains("://") -> URI(raw).host
                raw.startsWith("//") -> URI("https:$raw").host
                else -> URI("https://$raw").host
            }
        }.getOrNull()?.lowercase()?.trim('.').orEmpty()
        if (host.isBlank()) return null
        return host
    }
}
