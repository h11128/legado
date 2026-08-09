package io.legado.app.model.review

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookReviewBinding
import io.legado.app.data.entities.BookSource
import io.legado.app.help.config.AppConfig

/**
 * RFC-004 §6.2 / §12 effective mode for a shelf book.
 */
sealed class ReviewOverlayMode {
    /** Pref off or native-only; ignore overlay binding. */
    data object NativeOnly : ReviewOverlayMode()

    /** Use content origin review rules. */
    data object Native : ReviewOverlayMode()

    /** Load reviews from one or more bound providers. */
    data class Overlay(val bindings: List<BookReviewBinding>) : ReviewOverlayMode() {
        val primary: BookReviewBinding?
            get() = ReviewOverlayMerge.paragraphPrimary(bindings)
    }

    /** Overlay allowed but nothing bound yet. */
    data object Unbound : ReviewOverlayMode()
}

object ReviewOverlayResolver {

    /**
     * Prefer [ReviewOverlayBindings.listEnabled] (merge prefs already applied).
     * Raw lists: only enabled rows are kept; merge cap is not re-applied here
     * (avoids AppConfig in unit tests).
     */
    fun resolve(
        book: Book,
        originSource: BookSource?,
        bindings: List<BookReviewBinding>?,
        overlayEnabled: Boolean = AppConfig.reviewOverlayEnabled,
    ): ReviewOverlayMode {
        if (!overlayEnabled) return ReviewOverlayMode.NativeOnly
        val selected = bindings.orEmpty()
            .filter { it.enabled }
            .sortedWith(compareBy({ it.sortOrder }, { it.id }))
        val originCapable = originSource != null && ReviewCapability.isReviewCapable(originSource)
        if (selected.isNotEmpty()) {
            if (selected.size == 1 &&
                originCapable &&
                selected[0].providerSourceUrl == book.origin
            ) {
                return ReviewOverlayMode.Native
            }
            return ReviewOverlayMode.Overlay(selected)
        }
        if (originCapable) return ReviewOverlayMode.Native
        return ReviewOverlayMode.Unbound
    }
}
