package io.legado.app.model.review

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookReviewBinding
import io.legado.app.data.entities.BookSource
import io.legado.app.help.config.AppConfig

/**
 * RFC-004 §6.2 effective mode for a shelf book.
 */
sealed class ReviewOverlayMode {
    /** Pref off or native-only; ignore overlay binding. */
    data object NativeOnly : ReviewOverlayMode()

    /** Use content origin review rules. */
    data object Native : ReviewOverlayMode()

    /** Load reviews from bound provider. */
    data class Overlay(val binding: BookReviewBinding) : ReviewOverlayMode()

    /** Overlay allowed but nothing bound yet. */
    data object Unbound : ReviewOverlayMode()
}

object ReviewOverlayResolver {

    fun resolve(
        book: Book,
        originSource: BookSource?,
        binding: BookReviewBinding?,
        overlayEnabled: Boolean = AppConfig.reviewOverlayEnabled,
    ): ReviewOverlayMode {
        if (!overlayEnabled) return ReviewOverlayMode.NativeOnly
        val originCapable = originSource != null && ReviewCapability.isReviewCapable(originSource)
        if (binding != null) {
            if (originCapable && binding.providerSourceUrl == book.origin) {
                return ReviewOverlayMode.Native
            }
            return ReviewOverlayMode.Overlay(binding)
        }
        if (originCapable) return ReviewOverlayMode.Native
        return ReviewOverlayMode.Unbound
    }
}
