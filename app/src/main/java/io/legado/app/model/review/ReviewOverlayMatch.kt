package io.legado.app.model.review

import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.book.BookAuthorIdentity
import io.legado.app.model.checkalgo.ChangeChapterVerify
import io.legado.app.model.webBook.WebBook

/**
 * P1 helpers: find provider book hits and accept chapter align.
 */
object ReviewOverlayMatch {

    data class ProviderHit(
        val source: BookSource,
        val searchBook: SearchBook,
    )

    /**
     * Search [source] for [book]; return same-book hits only.
     * Empty / multi handled by caller (RFC-004 §6.3).
     */
    suspend fun searchSameBookHits(book: Book, source: BookSource): List<ProviderHit> {
        if (!ReviewCapability.isReviewCapable(source)) return emptyList()
        val raw = runCatching {
            WebBook.searchBookAwait(source, book.name, 1)
        }.getOrElse { emptyList() }
        if (raw.isEmpty()) return emptyList()
        val peers = raw.filter { BookAuthorIdentity.equalName(it.name, book.name) }
        if (peers.isEmpty()) return emptyList()
        return peers.mapNotNull { hit ->
            if (
                BookAuthorIdentity.sameBook(
                    book.name,
                    book.author,
                    hit.name,
                    hit.author,
                    peers.map { it.author },
                )
            ) {
                ProviderHit(source, hit)
            } else {
                null
            }
        }
    }

    fun acceptAlign(
        localIndex: Int,
        localTitle: String,
        providerToc: List<BookChapter>,
    ): ChangeChapterVerify.AlignResult? {
        val result = ChangeChapterVerify.alignResult(localIndex, localTitle, providerToc)
        return result?.takeIf { ReviewAlignConfig.acceptsChapterAlign(it.quality) }
    }

    fun listCapableEnabledSources(): List<BookSource> {
        return appDb.bookSourceDao.allTextEnabledPart.mapNotNull { part ->
            appDb.bookSourceDao.getBookSource(part.bookSourceUrl)
        }.filter { ReviewCapability.isReviewCapable(it) }
    }
}
