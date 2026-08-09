package io.legado.app.model.review

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.config.AppConfig
import io.legado.app.model.checkalgo.AskTimeout
import io.legado.app.model.checkalgo.RespondTimeRank
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * RFC-004 §6.7 / P3 — auto-discover a review provider (confirm required; never silent).
 * No-op when capableCount==0 (§6.0) or pref off.
 */
object ReviewOverlayAutoBind {

    data class Proposal(
        val source: BookSource,
        val providerBookUrl: String,
        val providerName: String,
        val providerAuthor: String,
    )

    /**
     * Probe capable sources for a unique same-book hit.
     * Does not persist; caller must snackbar-confirm then [ReviewOverlayBindings.bindAuto].
     */
    suspend fun propose(
        book: Book,
        cap: Int = ReviewAlignConfig.AUTO_DISCOVERY_CAP,
    ): Proposal? = withContext(Dispatchers.IO) {
        if (!AppConfig.reviewOverlayEnabled || !AppConfig.reviewOverlayAutoBind) return@withContext null
        if (ReviewOverlayBindings.list(book.bookUrl).isNotEmpty()) return@withContext null
        val capable = ReviewOverlayMatch.listCapableEnabledSources()
        if (capable.isEmpty()) {
            AppLog.put("ReviewOverlay auto-bind skip: capableCount=0")
            return@withContext null
        }
        proposeFromCapable(
            book = book,
            capable = capable,
            cap = cap,
            searchHits = { content, source ->
                withTimeoutOrNull(AskTimeout.SEARCH_MS) {
                    ReviewOverlayMatch.searchSameBookHits(content, source)
                } ?: emptyList()
            },
        )
    }

    /**
     * Pure selection over already-listed capable sources (unit-testable).
     * [searchHits] should return same-book hits only; empty / multi → skip source.
     */
    internal suspend fun proposeFromCapable(
        book: Book,
        capable: List<BookSource>,
        cap: Int = ReviewAlignConfig.AUTO_DISCOVERY_CAP,
        searchHits: suspend (Book, BookSource) -> List<ReviewOverlayMatch.ProviderHit>,
    ): Proposal? {
        if (capable.isEmpty()) {
            return null
        }
        val ordered = orderCapableSources(capable).take(cap.coerceAtLeast(1))
        for (source in ordered) {
            val hits = runCatching { searchHits(book, source) }.getOrElse { emptyList() }
            when (hits.size) {
                1 -> {
                    val hit = hits[0].searchBook
                    return Proposal(
                        source = source,
                        providerBookUrl = hit.bookUrl,
                        providerName = hit.name,
                        providerAuthor = hit.author,
                    )
                }
                else -> {
                    // 0 or ≥2: skip this source (no auto on ambiguity)
                }
            }
        }
        return null
    }

    internal fun orderCapableSources(capable: List<BookSource>): List<BookSource> =
        capable.sortedWith(
            compareBy<BookSource> { RespondTimeRank.classify(it.respondTime) }
                .thenBy { it.respondTime }
                .thenBy { it.customOrder }
        )
}
