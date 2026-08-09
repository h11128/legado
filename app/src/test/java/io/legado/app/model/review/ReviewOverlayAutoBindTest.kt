package io.legado.app.model.review

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewOverlayAutoBindTest {

    private fun source(url: String, name: String = url, respondTime: Long = 100, order: Int = 0) =
        BookSource().apply {
            bookSourceUrl = url
            bookSourceName = name
            this.respondTime = respondTime
            customOrder = order
        }

    private fun hit(source: BookSource, bookUrl: String, name: String, author: String) =
        ReviewOverlayMatch.ProviderHit(
            source = source,
            searchBook = SearchBook(
                name = name,
                author = author,
                bookUrl = bookUrl,
                origin = source.bookSourceUrl,
                originName = source.bookSourceName,
            ),
        )

    private fun proposal(url: String, bookUrl: String = "prov://$url") =
        ReviewOverlayAutoBind.Proposal(
            source = source(url),
            providerBookUrl = bookUrl,
            providerName = "我的书",
            providerAuthor = "甲",
        )

    @Test
    fun proposeNullWhenCapableEmpty() = runBlocking {
        val book = Book(name = "我的书", author = "甲", bookUrl = "content://a")
        val result = ReviewOverlayAutoBind.proposeFromCapable(
            book = book,
            capable = emptyList(),
            searchHits = { _, _ -> error("must not search") },
        )
        assertNull(result)
    }

    @Test
    fun proposeUniqueHit() = runBlocking {
        val source = source("legado-fixture://review-overlay", "fixture")
        val book = Book(name = "我的书", author = "甲", bookUrl = "content://a")
        val result = ReviewOverlayAutoBind.proposeFromCapable(
            book = book,
            capable = listOf(source),
            searchHits = { _, s -> listOf(hit(s, "prov://1", "我的书", "甲")) },
        )
        assertEquals("prov://1", result?.providerBookUrl)
        assertEquals(source.bookSourceUrl, result?.source?.bookSourceUrl)
    }

    @Test
    fun proposeSkipsAmbiguousThenTakesNextUnique() = runBlocking {
        val ambiguous = source("https://a", "a", respondTime = 10)
        val unique = source("https://b", "b", respondTime = 20)
        val book = Book(name = "我的书", author = "甲", bookUrl = "content://a")
        val result = ReviewOverlayAutoBind.proposeFromCapable(
            book = book,
            capable = listOf(ambiguous, unique),
            searchHits = { _, s ->
                when (s.bookSourceUrl) {
                    "https://a" -> listOf(
                        hit(s, "p1", "我的书", "甲"),
                        hit(s, "p2", "我的书", "甲"),
                    )
                    else -> listOf(hit(s, "p-ok", "我的书", "甲"))
                }
            },
        )
        assertEquals("p-ok", result?.providerBookUrl)
        assertEquals("https://b", result?.source?.bookSourceUrl)
    }

    @Test
    fun proposeAllCollectsMultipleUniqueHits() = runBlocking {
        val a = source("https://a", respondTime = 10)
        val b = source("https://b", respondTime = 20)
        val cAmbiguous = source("https://c", respondTime = 30)
        val book = Book(name = "我的书", author = "甲", bookUrl = "content://a")
        val result = ReviewOverlayAutoBind.proposeAllFromCapable(
            book = book,
            capable = listOf(a, b, cAmbiguous),
            maxResults = 5,
            searchHits = { _, s ->
                when (s.bookSourceUrl) {
                    "https://c" -> listOf(
                        hit(s, "c1", "我的书", "甲"),
                        hit(s, "c2", "我的书", "甲"),
                    )
                    else -> listOf(hit(s, "p-${s.bookSourceUrl}", "我的书", "甲"))
                }
            },
        )
        assertEquals(2, result.size)
        assertEquals(listOf("https://a", "https://b"), result.map { it.source.bookSourceUrl })
    }

    @Test
    fun proposeAllRespectsMaxAndSkip() = runBlocking {
        val a = source("https://a", respondTime = 10)
        val b = source("https://b", respondTime = 20)
        val c = source("https://c", respondTime = 30)
        val book = Book(name = "我的书", author = "甲", bookUrl = "content://a")
        val result = ReviewOverlayAutoBind.proposeAllFromCapable(
            book = book,
            capable = listOf(a, b, c),
            maxResults = 2,
            skipProviderSourceUrls = setOf("https://a"),
            searchHits = { _, s -> listOf(hit(s, "p-${s.bookSourceUrl}", "我的书", "甲")) },
        )
        assertEquals(listOf("https://b", "https://c"), result.map { it.source.bookSourceUrl })
    }

    @Test
    fun selectNewAutoProposalsSkipsExistingAndCaps() {
        val proposals = listOf(
            proposal("https://a"),
            proposal("https://b"),
            proposal("https://c"),
            proposal("https://d"),
        )
        val selected = ReviewOverlayBindings.selectNewAutoProposals(
            proposals = proposals,
            existingProviderUrls = setOf("https://b"), // disabled or manual — still skip
            existingCount = 1,
            mergeMax = 3,
        )
        assertEquals(listOf("https://a", "https://c"), selected.map { it.source.bookSourceUrl })
    }

    @Test
    fun selectNewAutoProposalsEmptyWhenSlotsFull() {
        val selected = ReviewOverlayBindings.selectNewAutoProposals(
            proposals = listOf(proposal("https://a")),
            existingProviderUrls = setOf("https://x", "https://y"),
            existingCount = 5,
            mergeMax = 5,
        )
        assertTrue(selected.isEmpty())
    }

    @Test
    fun orderPrefersFasterRespondTime() {
        val slow = source("https://slow", respondTime = 5_000)
        val fast = source("https://fast", respondTime = 100)
        val ordered = ReviewOverlayAutoBind.orderCapableSources(listOf(slow, fast))
        assertEquals("https://fast", ordered.first().bookSourceUrl)
        assertTrue(ordered.size == 2)
    }
}
