package io.legado.app.model.review

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookReviewBinding

/**
 * In-memory overlay session while reading (RFC-004 P1 bucket + P2 para map).
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
        /** Provider `-1` count when [chapterBucket] is present (for detail toolbar). */
        val chapterBucketCount: Int = 0,
        /** Local body review id → provider ref. Empty when P2 closed or coverage fail. */
        val paraRefs: Map<Int, ProviderParaRef> = emptyMap(),
    ) {
        fun toOverlaySession(): ReviewOverlaySession = ReviewOverlaySession(
            providerSourceUrl = providerSourceKey,
            providerBookUrl = providerBook.bookUrl,
            providerChapterIndex = providerChapterIndex,
            providerChapterUrl = providerChapter.url,
            chapterBucket = chapterBucket,
            paraRefs = paraRefs,
        )
    }

    /** RFC-004 §12 multi-provider chapter session. */
    data class MergeActive(
        val contentBookUrl: String,
        val contentChapterIndex: Int,
        val providers: List<Active>,
        val mergedBucketCount: Int,
        /** Session used for paragraph icons / para clicks. */
        val paragraphPrimary: Active?,
    ) {
        fun providersWithBucket(): List<Active> =
            providers.filter { it.chapterBucket != null }
    }

    @Volatile
    private var active: Active? = null

    @Volatile
    private var mergeActive: MergeActive? = null

    private val providerBookByUrl = HashMap<String, Book>()
    private val providerTocByUrl = HashMap<String, List<BookChapter>>()

    @Synchronized
    fun clear() {
        active = null
        mergeActive = null
    }

    /** Drop chapter-level overlay state; keep provider book/TOC caches. */
    @Synchronized
    fun clearChapter() {
        active = null
        mergeActive = null
    }

    @Synchronized
    fun put(session: Active) {
        val cur = active
        // Never let a late chapter-bucket-only put wipe a richer P2 session for the same chapter.
        if (cur != null &&
            cur.contentBookUrl == session.contentBookUrl &&
            cur.contentChapterIndex == session.contentChapterIndex &&
            cur.binding.providerSourceUrl == session.binding.providerSourceUrl &&
            cur.binding.providerBookUrl == session.binding.providerBookUrl &&
            cur.paraRefs.isNotEmpty() &&
            session.paraRefs.isEmpty()
        ) {
            return
        }
        active = session
        mergeActive = null
        putProviderBook(session.providerBook)
        putProviderToc(session.providerBook.bookUrl, session.providerToc)
    }

    @Synchronized
    fun putMerge(session: MergeActive) {
        mergeActive = session
        active = session.paragraphPrimary ?: session.providers.firstOrNull()
        for (p in session.providers) {
            putProviderBook(p.providerBook)
            putProviderToc(p.providerBook.bookUrl, p.providerToc)
        }
    }

    @Synchronized
    fun get(): Active? = active

    @Synchronized
    fun getMerge(): MergeActive? = mergeActive

    fun matchesContentChapter(contentBookUrl: String, contentChapterIndex: Int): Boolean {
        synchronized(this) {
            mergeActive?.let {
                return it.contentBookUrl == contentBookUrl &&
                        it.contentChapterIndex == contentChapterIndex
            }
            val session = active ?: return false
            return session.contentBookUrl == contentBookUrl &&
                    session.contentChapterIndex == contentChapterIndex
        }
    }

    @Synchronized
    fun providerBookFor(bookUrl: String): Book? {
        active?.providerBook?.takeIf { it.bookUrl == bookUrl }?.let { return it }
        mergeActive?.providers?.firstOrNull { it.providerBook.bookUrl == bookUrl }
            ?.let { return it.providerBook }
        return providerBookByUrl[bookUrl]
    }

    @Synchronized
    fun providerChapter(bookUrl: String, chapterIndex: Int): BookChapter? {
        fun fromSession(session: Active): BookChapter? {
            if (session.providerBook.bookUrl != bookUrl) return null
            if (session.providerChapterIndex == chapterIndex) return session.providerChapter
            return session.providerToc.firstOrNull { it.index == chapterIndex }
        }
        active?.let { fromSession(it)?.let { ch -> return ch } }
        mergeActive?.providers?.forEach { fromSession(it)?.let { ch -> return ch } }
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
