package io.legado.app.model.review

import io.legado.app.data.entities.BookReviewBinding

/**
 * RFC-004 §12 pure helpers for multi-provider selection / counts.
 * Defaults are literals so unit tests do not touch [io.legado.app.help.config.AppConfig].
 */
object ReviewOverlayMerge {

    fun selectBindings(
        all: List<BookReviewBinding>,
        mergeEnabled: Boolean = true,
        mergeMax: Int = 5,
    ): List<BookReviewBinding> {
        val enabled = all.filter { it.enabled }
            .sortedWith(compareBy({ it.sortOrder }, { it.id }))
        if (enabled.isEmpty()) return emptyList()
        if (!mergeEnabled) return listOf(enabled.first())
        return enabled.take(mergeMax.coerceAtLeast(1))
    }

    fun sumChapterBucketCount(counts: List<Int>): Int =
        counts.sumOf { it.coerceAtLeast(0) }

    fun paragraphPrimary(bindings: List<BookReviewBinding>): BookReviewBinding? {
        val primary = bindings.firstOrNull {
            it.enabled && it.role == BookReviewBinding.ROLE_PARAGRAPH_PRIMARY
        }
        return primary ?: bindings.firstOrNull { it.enabled }
    }
}
