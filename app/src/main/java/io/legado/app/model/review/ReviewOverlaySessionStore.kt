package io.legado.app.model.review

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookReviewBinding

/**
 * In-memory overlay session while reading (RFC-004 P1 chapter-bucket).
 * Process-local only; not persisted.
 */
object ReviewOverlaySessionStore {

    data class Active(
        val contentBookUrl: String,
        val contentChapterIndex: Int,
        val binding: BookReviewBinding,
        val providerSourceKey: String,
        val providerBook: Book,
        val providerToc: List<BookChapter>,
        val providerChapterIndex: Int,
        val providerChapter: BookChapter,
        val alignQuality: Double,
        /** Present only when provider summary has paraIndex=-1 with count>0. */
        val chapterBucket: ProviderParaRef?,
    ) {
        fun toOverlaySession(): ReviewOverlaySession = ReviewOverlaySession(
            providerSourceUrl = providerSourceKey,
            providerBookUrl = providerBook.bookUrl,
            providerChapterIndex = providerChapterIndex,
            providerChapterUrl = providerChapter.url,
            chapterBucket = chapterBucket,
        )
    }

    @Volatile
    private var active: Active? = null

    private val providerBookByUrl = HashMap<String, Book>()
    private val providerTocByUrl = HashMap<String, List<BookChapter>>()

    @Synchronized
    fun clear() {
        active = null
    }

    /** Drop chapter-level overlay state; keep provider book/TOC caches. */
    @Synchronized
    fun clearChapter() {
        active = null
    }

    @Synchronized
    fun put(session: Active) {
        active = session
        putProviderBook(session.providerBook)
        putProviderToc(session.providerBook.bookUrl, session.providerToc)
    }

    @Synchronized
    fun get(): Active? = active

    fun matchesContentChapter(contentBookUrl: String, contentChapterIndex: Int): Boolean {
        val session = active ?: return false
        return session.contentBookUrl == contentBookUrl &&
                session.contentChapterIndex == contentChapterIndex
    }

    @Synchronized
    fun providerBookFor(bookUrl: String): Book? {
        active?.providerBook?.takeIf { it.bookUrl == bookUrl }?.let { return it }
        return providerBookByUrl[bookUrl]
    }

    @Synchronized
    fun providerChapter(bookUrl: String, chapterIndex: Int): BookChapter? {
        active?.takeIf { it.providerBook.bookUrl == bookUrl }?.let { session ->
            if (session.providerChapterIndex == chapterIndex) return session.providerChapter
            session.providerToc.firstOrNull { it.index == chapterIndex }?.let { return it }
        }
        return providerTocByUrl[bookUrl]?.firstOrNull { it.index == chapterIndex }
    }

    @Synchronized
    fun cachedProviderBook(providerBookUrl: String): Book? = providerBookByUrl[providerBookUrl]

    @Synchronized
    fun putProviderBook(book: Book) {
        providerBookByUrl[book.bookUrl] = book
    }

    @Synchronized
    fun cachedProviderToc(providerBookUrl: String): List<BookChapter>? =
        providerTocByUrl[providerBookUrl]

    @Synchronized
    fun putProviderToc(providerBookUrl: String, toc: List<BookChapter>) {
        providerTocByUrl[providerBookUrl] = toc
    }

    @Synchronized
    fun clearCaches() {
        providerBookByUrl.clear()
        providerTocByUrl.clear()
    }
}
