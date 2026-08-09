package io.legado.app.model.review

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookReviewBinding
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReviewOverlaySessionStoreTest {

    @Before
    @After
    fun clear() {
        ReviewOverlaySessionStore.clear()
        ReviewOverlaySessionStore.clearCaches()
    }

    private fun active(
        contentBookUrl: String = "content://book",
        chapterIndex: Int = 0,
        provider: String = "https://provider/a",
        paraData: String? = "bucket-a",
    ): ReviewOverlaySessionStore.Active {
        val book = Book(bookUrl = "$provider/book", name = "p", author = "", origin = provider)
        val chapter = BookChapter(url = "$provider/ch", title = "c1", index = chapterIndex)
        return ReviewOverlaySessionStore.Active(
            contentBookUrl = contentBookUrl,
            contentChapterIndex = chapterIndex,
            binding = BookReviewBinding(
                contentBookUrl = contentBookUrl,
                providerSourceUrl = provider,
                providerBookUrl = book.bookUrl,
            ),
            providerSourceKey = provider,
            providerBook = book,
            providerToc = listOf(chapter),
            providerChapterIndex = chapterIndex,
            providerChapter = chapter,
            alignQuality = 0.9,
            chapterBucket = paraData?.let {
                ProviderParaRef(providerParaIndex = -1, paraData = it)
            },
            chapterBucketCount = if (paraData != null) 3 else 0,
        )
    }

    @Test
    fun putMergeThenGetMergeMatchesContentChapter() {
        val a = active(provider = "https://a", paraData = "pa")
        val b = active(provider = "https://b", paraData = "pb")
        val merge = ReviewOverlaySessionStore.MergeActive(
            contentBookUrl = "content://book",
            contentChapterIndex = 0,
            providers = listOf(a, b),
            mergedBucketCount = 5,
            paragraphPrimary = a,
        )
        ReviewOverlaySessionStore.putMerge(merge)
        assertEquals(2, ReviewOverlaySessionStore.getMerge()?.providers?.size)
        assertEquals(2, ReviewOverlaySessionStore.getMerge()?.providersWithBucket()?.size)
        assertTrue(ReviewOverlaySessionStore.matchesContentChapter("content://book", 0))
        assertEquals(a.providerSourceKey, ReviewOverlaySessionStore.get()?.providerSourceKey)
    }

    @Test
    fun clearChapterDropsMergeSession() {
        ReviewOverlaySessionStore.putMerge(
            ReviewOverlaySessionStore.MergeActive(
                contentBookUrl = "content://book",
                contentChapterIndex = 1,
                providers = listOf(active(chapterIndex = 1)),
                mergedBucketCount = 1,
                paragraphPrimary = active(chapterIndex = 1),
            )
        )
        ReviewOverlaySessionStore.clearChapter()
        assertNull(ReviewOverlaySessionStore.getMerge())
        assertNull(ReviewOverlaySessionStore.get())
    }

    @Test
    fun providersWithBucketSkipsMissingChapterBucket() {
        val with = active(provider = "https://with", paraData = "x")
        val without = active(provider = "https://without", paraData = null)
        ReviewOverlaySessionStore.putMerge(
            ReviewOverlaySessionStore.MergeActive(
                contentBookUrl = "content://book",
                contentChapterIndex = 0,
                providers = listOf(with, without),
                mergedBucketCount = 1,
                paragraphPrimary = with,
            )
        )
        assertEquals(
            listOf("https://with"),
            ReviewOverlaySessionStore.getMerge()?.providersWithBucket()
                ?.map { it.providerSourceKey },
        )
    }
}
