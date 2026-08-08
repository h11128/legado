package io.legado.app.model.webBook

import io.legado.app.data.entities.SearchBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchBookMergeTest {

    private fun book(
        name: String,
        author: String,
        origin: String,
        intro: String? = null,
    ) = SearchBook(
        name = name,
        author = author,
        origin = origin,
        bookUrl = "$origin/$name",
        intro = intro,
    )

    @Test
    fun exactNameAndAuthorStillMerge() {
        val a = book("同时穿越了99个世界", "凤嘲凰", "https://a.com")
        val b = book("同时穿越了99个世界", "凤嘲凰", "https://b.com")
        assertTrue(SearchBookMerge.sameBookForMerge(a, b, listOf(a)))
    }

    @Test
    fun emptyAuthorMergesIntoSoleNonEmptyAuthor() {
        val withAuthor = book("同时穿越了99个世界", "凤嘲凰", "https://a.com")
        val empty = book("同时穿越了99个世界", "", "https://empty.com")
        assertTrue(
            SearchBookMerge.sameBookForMerge(withAuthor, empty, listOf(withAuthor))
        )
        assertTrue(
            SearchBookMerge.sameBookForMerge(empty, withAuthor, listOf(empty))
        )
    }

    @Test
    fun emptyAuthorDoesNotMergeWhenNameHasMultipleAuthors() {
        val a = book("同名书", "作者甲", "https://a.com")
        val b = book("同名书", "作者乙", "https://b.com")
        val empty = book("同名书", "", "https://empty.com")
        val peers = listOf(a, b)
        assertFalse(SearchBookMerge.sameBookForMerge(a, empty, peers))
        assertFalse(SearchBookMerge.sameBookForMerge(b, empty, peers))
        assertFalse(SearchBookMerge.sameBookForMerge(empty, a, peers))
    }

    @Test
    fun differentNonEmptyAuthorsNeverMerge() {
        val a = book("同名书", "作者甲", "https://a.com")
        val b = book("同名书", "作者乙", "https://b.com")
        assertFalse(SearchBookMerge.sameBookForMerge(a, b, listOf(a)))
    }

    @Test
    fun bothEmptyAuthorsMergeByExactAuthorEquality() {
        val a = book("同时穿越了99个世界", "", "https://a.com")
        val b = book("同时穿越了99个世界", "", "https://b.com")
        assertTrue(SearchBookMerge.sameBookForMerge(a, b, listOf(a)))
    }

    @Test
    fun absorbCopiesAuthorAndOriginOntoEmptyTarget() {
        val empty = book("同时穿越了99个世界", "", "https://empty.com", intro = null)
        empty.coverUrl = null
        val rich = book(
            "同时穿越了99个世界",
            "凤嘲凰",
            "https://rich.com",
            intro = "简介……",
        )
        rich.coverUrl = "https://rich.com/cover.jpg"
        SearchBookMerge.absorb(empty, rich)
        assertEquals("凤嘲凰", empty.author)
        assertEquals("简介……", empty.intro)
        assertEquals("https://rich.com/cover.jpg", empty.coverUrl)
        assertTrue(empty.origins.contains("https://rich.com"))
        assertTrue(empty.origins.contains("https://empty.com"))
    }

    @Test
    fun absorbDoesNotOverwriteExistingNonEmptyAuthor() {
        val target = book("同时穿越了99个世界", "凤嘲凰", "https://a.com")
        val other = book("同时穿越了99个世界", "", "https://b.com")
        SearchBookMerge.absorb(target, other)
        assertEquals("凤嘲凰", target.author)
    }

    @Test
    fun emptyDoesNotMergeOnceSecondAuthorAppearsInPeers() {
        // After both authors exist in the bucket, empty must stay separate (rule 1).
        val a = book("同名书", "作者甲", "https://a.com")
        val b = book("同名书", "作者乙", "https://b.com")
        val empty = book("同名书", "", "https://empty.com")
        assertFalse(SearchBookMerge.sameBookForMerge(a, empty, listOf(a, b)))
    }
}
