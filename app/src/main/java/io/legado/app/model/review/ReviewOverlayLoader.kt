package io.legado.app.model.review

import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookReviewBinding
import io.legado.app.data.entities.BookSource
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.ReviewRuleParser
import io.legado.app.model.jsSource.JsSourceReview
import io.legado.app.model.webBook.WebBook
import kotlin.coroutines.coroutineContext

/**
 * RFC-004 P1: load provider chapter-bucket summary (paraIndex == -1 only).
 */
internal object ReviewOverlayLoader {

    data class Result(
        val summary: ReviewRuleParser.SummaryResult,
        val session: ReviewOverlaySessionStore.Active?,
        val alignQuality: Double?,
        val providerChapterIndex: Int?,
    ) {
        companion object {
            fun empty(): Result = Result(
                summary = ReviewRuleParser.SummaryResult(emptyMap(), emptyMap()),
                session = null,
                alignQuality = null,
                providerChapterIndex = null,
            )
        }
    }

    suspend fun loadChapterBucket(
        contentBook: Book,
        contentChapter: BookChapter,
        binding: BookReviewBinding,
    ): Result {
        val source = appDb.bookSourceDao.getBookSource(binding.providerSourceUrl)
        if (source == null || !ReviewCapability.isReviewCapable(source)) {
            ReviewOverlaySessionStore.clearChapter()
            AppLog.put(
                "ReviewOverlay bind=${binding.providerSourceUrl} align=n/a bucket=0 " +
                        "(provider source missing or not review-capable)"
            )
            return Result.empty()
        }

        val providerBook = ensureProviderBook(source, binding) ?: run {
            ReviewOverlaySessionStore.clearChapter()
            AppLog.put(
                "ReviewOverlay bind=${binding.providerSourceUrl} align=n/a bucket=0 " +
                        "(provider book resolve failed)"
            )
            return Result.empty()
        }

        val toc = ensureProviderToc(source, providerBook)
        val align = ReviewOverlayMatch.acceptAlign(
            contentChapter.index,
            contentChapter.title,
            toc,
        )
        if (align == null) {
            ReviewOverlaySessionStore.clearChapter()
            AppLog.put(
                "ReviewOverlay bind=${binding.providerSourceUrl} align=fail bucket=0 " +
                        "contentChapter=${contentChapter.index}"
            )
            return Result.empty()
        }

        val providerChapter = toc.getOrNull(align.index)
        if (providerChapter == null || providerChapter.isVolume) {
            ReviewOverlaySessionStore.clearChapter()
            AppLog.put(
                "ReviewOverlay bind=${binding.providerSourceUrl} align=${align.quality} " +
                        "bucket=0 (provider chapter missing)"
            )
            return Result.empty()
        }

        val rawSummary = runCatching {
            fetchSummary(source, providerBook, providerChapter)
        }.onFailure {
            AppLog.put(
                "ReviewOverlay bind=${binding.providerSourceUrl} summary fetch failed\n" +
                        "${it.localizedMessage}",
                it,
            )
        }.getOrNull() ?: ReviewRuleParser.SummaryResult(emptyMap(), emptyMap())

        val bucketSummary = chapterBucketOnly(rawSummary)
        // Icon can show count without paraData; click requires a real provider key (§6.9.1).
        val chapterBucket = bucketRef(bucketSummary)
        val displaySummary = if (chapterBucket != null) {
            bucketSummary
        } else {
            // Count without key → show nothing tappable (do not invent paraData).
            ReviewRuleParser.SummaryResult(emptyMap(), emptyMap())
        }
        val session = ReviewOverlaySessionStore.Active(
            contentBookUrl = contentBook.bookUrl,
            contentChapterIndex = contentChapter.index,
            binding = binding,
            providerSourceKey = source.getKey(),
            providerBook = providerBook,
            providerToc = toc,
            providerChapterIndex = providerChapter.index,
            providerChapter = providerChapter,
            alignQuality = align.quality,
            chapterBucket = chapterBucket,
        )
        ReviewOverlaySessionStore.put(session)
        AppLog.put(
            "ReviewOverlay bind=${binding.providerSourceUrl} align=${align.quality} " +
                    "bucket=${displaySummary.counts[-1] ?: 0}"
        )
        return Result(
            summary = displaySummary,
            session = session,
            alignQuality = align.quality,
            providerChapterIndex = providerChapter.index,
        )
    }

    /**
     * Keep only chapter-bucket entries (paraIndex == -1). Never invent a fake -1.
     */
    fun chapterBucketOnly(summary: ReviewRuleParser.SummaryResult): ReviewRuleParser.SummaryResult {
        val count = summary.counts[-1]?.takeIf { it > 0 }
            ?: return ReviewRuleParser.SummaryResult(emptyMap(), emptyMap())
        val key = summary.keys[-1]
        return ReviewRuleParser.SummaryResult(
            counts = mapOf(-1 to count),
            keys = if (key.isNullOrBlank()) emptyMap() else mapOf(-1 to key),
        )
    }

    private fun bucketRef(summary: ReviewRuleParser.SummaryResult): ProviderParaRef? {
        val count = summary.counts[-1] ?: return null
        if (count <= 0) return null
        val paraData = summary.keys[-1]?.takeIf { it.isNotBlank() } ?: return null
        return ProviderParaRef(providerParaIndex = -1, paraData = paraData)
    }

    private suspend fun ensureProviderBook(
        source: BookSource,
        binding: BookReviewBinding,
    ): Book? {
        ReviewOverlaySessionStore.cachedProviderBook(binding.providerBookUrl)?.let { cached ->
            if (cached.tocUrl.isNotBlank() || cached.bookUrl.isNotBlank()) return cached
        }
        val seed = Book(
            name = binding.providerName,
            author = binding.providerAuthor,
            bookUrl = binding.providerBookUrl,
            origin = binding.providerSourceUrl,
            originName = source.bookSourceName,
        )
        return runCatching {
            if (seed.tocUrl.isBlank()) {
                WebBook.getBookInfoAwait(source, seed, canReName = false)
            } else {
                seed
            }
        }.onSuccess {
            ReviewOverlaySessionStore.putProviderBook(it)
        }.getOrNull()
    }

    private suspend fun ensureProviderToc(
        source: BookSource,
        providerBook: Book,
    ): List<BookChapter> {
        ReviewOverlaySessionStore.cachedProviderToc(providerBook.bookUrl)?.let { return it }
        val toc = WebBook.getChapterListAwait(source, providerBook)
            .getOrElse { emptyList() }
        if (toc.isNotEmpty()) {
            ReviewOverlaySessionStore.putProviderToc(providerBook.bookUrl, toc)
            ReviewOverlaySessionStore.putProviderBook(providerBook)
        }
        return toc
    }

    private suspend fun fetchSummary(
        source: BookSource,
        book: Book,
        chapter: BookChapter,
    ): ReviewRuleParser.SummaryResult? {
        if (source.isJsSource()) {
            return JsSourceReview.getReviewSummaryAwait(source, book, chapter)
        }
        val rule = source.ruleReview ?: return null
        val summaryUrl = rule.reviewSummaryUrl?.takeIf { it.isNotBlank() } ?: return null
        if (!rule.enabled ||
            rule.summaryListRule.isNullOrBlank() ||
            rule.summaryParagraphIndexRule.isNullOrBlank() ||
            rule.summaryCountRule.isNullOrBlank()
        ) {
            return null
        }
        val analyzeUrl = AnalyzeUrl(
            summaryUrl,
            baseUrl = chapter.url,
            source = source,
            ruleData = book,
            chapter = chapter,
            coroutineContext = coroutineContext,
        )
        val body = analyzeUrl.getStrResponseAwait(useWebView = false).body ?: return null
        return ReviewRuleParser.parseSummary(
            body,
            rule,
            source,
            book,
            chapter,
            analyzeUrl.url,
            coroutineContext,
        )
    }
}
