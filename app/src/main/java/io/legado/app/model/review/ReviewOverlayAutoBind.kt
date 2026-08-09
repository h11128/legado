package io.legado.app.model.review

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.config.AppConfig
import io.legado.app.model.checkalgo.AskTimeout
import io.legado.app.model.checkalgo.RespondTimeRank
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * RFC-004 P3 — auto-discover review providers (silent multi-bind when unique same-book hit).
 * No-op when capableCount==0 (§6.0) or pref off. Ambiguous (≥2 hits) sources are skipped.
 */
object ReviewOverlayAutoBind {

    data class Proposal(
        val source: BookSource,
        val providerBookUrl: String,
        val providerName: String,
        val providerAuthor: String,
    )

    /**
     * Sources that often return fanfic / wrong-book unique titles under keyword search.
     * Silent auto-bind is skipped; UI should ask before binding.
     */
    fun requiresBindConfirm(providerSourceUrl: String): Boolean {
        val u = providerSourceUrl.lowercase()
        return "fanqienovel.com" in u ||
            "101.35.133.34" in u || // community mirror used by fanqie provider
            "wtzw.com" in u // 七猫 — blocked supply, never silent
    }

    fun partitionSilentAndConfirm(
        proposals: List<Proposal>,
    ): Pair<List<Proposal>, List<Proposal>> {
        val silent = ArrayList<Proposal>()
        val confirm = ArrayList<Proposal>()
        for (p in proposals) {
            if (requiresBindConfirm(p.source.bookSourceUrl)) confirm.add(p) else silent.add(p)
        }
        return silent to confirm
    }

    /**
     * Probe capable sources for unique same-book hits (up to [maxResults]).
     * Does not persist; caller should [ReviewOverlayBindings.bindAutoAll].
     */
    suspend fun proposeAll(
        book: Book,
        cap: Int = ReviewAlignConfig.AUTO_DISCOVERY_CAP,
        maxResults: Int = AppConfig.reviewOverlayMergeMax,
        skipProviderSourceUrls: Set<String> = emptySet(),
    ): List<Proposal> = withContext(Dispatchers.IO) {
        if (!AppConfig.reviewOverlayEnabled || !AppConfig.reviewOverlayAutoBind) {
            return@withContext emptyList()
        }
        val capable = ReviewOverlayMatch.listCapableEnabledSources()
        if (capable.isEmpty()) {
            AppLog.put("ReviewOverlay auto-bind skip: capableCount=0")
            return@withContext emptyList()
        }
        AppLog.put(
            "ReviewOverlay auto-bind scan capable=${capable.size} " +
                    "skip=${skipProviderSourceUrls.size} max=$maxResults"
        )
        val proposals = proposeAllFromCapable(
            book = book,
            capable = capable,
            cap = cap,
            maxResults = maxResults,
            skipProviderSourceUrls = skipProviderSourceUrls,
            searchHits = { content, source ->
                withTimeoutOrNull(AskTimeout.SEARCH_MS) {
                    ReviewOverlayMatch.searchSameBookHits(content, source)
                } ?: emptyList()
            },
        )
        AppLog.put("ReviewOverlay auto-bind proposals=${proposals.size}")
        proposals
    }

    /** Compat: first unique proposal, or null. */
    suspend fun propose(
        book: Book,
        cap: Int = ReviewAlignConfig.AUTO_DISCOVERY_CAP,
    ): Proposal? = proposeAll(book, cap = cap, maxResults = 1).firstOrNull()

    /**
     * Pure multi-select over already-listed capable sources (unit-testable).
     * Searches run in parallel; results are accepted in [orderCapableSources] order.
     * [searchHits] should return same-book hits only; empty / multi → skip that source.
     */
    internal suspend fun proposeAllFromCapable(
        book: Book,
        capable: List<BookSource>,
        cap: Int = ReviewAlignConfig.AUTO_DISCOVERY_CAP,
        maxResults: Int = 5,
        skipProviderSourceUrls: Set<String> = emptySet(),
        searchHits: suspend (Book, BookSource) -> List<ReviewOverlayMatch.ProviderHit>,
    ): List<Proposal> {
        if (capable.isEmpty() || maxResults <= 0) return emptyList()
        val skip = skipProviderSourceUrls
        val ordered = orderCapableSources(capable)
            .filter { it.bookSourceUrl !in skip }
            .take(cap.coerceAtLeast(1))
        if (ordered.isEmpty()) return emptyList()

        val hitLists = coroutineScope {
            ordered.map { source ->
                async {
                    source to runCatching { searchHits(book, source) }.getOrElse { emptyList() }
                }
            }.awaitAll()
        }

        val out = ArrayList<Proposal>(maxResults.coerceAtMost(ordered.size))
        for ((source, hits) in hitLists) {
            if (out.size >= maxResults) break
            if (hits.size != 1) continue
            val hit = hits[0].searchBook
            out.add(
                Proposal(
                    source = source,
                    providerBookUrl = hit.bookUrl,
                    providerName = hit.name,
                    providerAuthor = hit.author,
                )
            )
        }
        return out
    }

    /** Compat wrapper used by older unit tests. */
    internal suspend fun proposeFromCapable(
        book: Book,
        capable: List<BookSource>,
        cap: Int = ReviewAlignConfig.AUTO_DISCOVERY_CAP,
        searchHits: suspend (Book, BookSource) -> List<ReviewOverlayMatch.ProviderHit>,
    ): Proposal? = proposeAllFromCapable(
        book = book,
        capable = capable,
        cap = cap,
        maxResults = 1,
        searchHits = searchHits,
    ).firstOrNull()

    internal fun orderCapableSources(capable: List<BookSource>): List<BookSource> =
        capable.sortedWith(
            compareBy<BookSource> { RespondTimeRank.classify(it.respondTime) }
                .thenBy { it.respondTime }
                .thenBy { it.customOrder }
        )
}
