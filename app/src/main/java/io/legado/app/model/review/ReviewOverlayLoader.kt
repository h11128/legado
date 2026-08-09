package io.legado.app.model.review

import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookReviewBinding
import io.legado.app.data.entities.BookSource
import io.legado.app.help.config.AppConfig
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.ReviewRuleParser
import io.legado.app.model.jsSource.JsSourceReview
import io.legado.app.model.webBook.WebBook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

/**
 * RFC-004: load provider review summary — P1 chapter-bucket, P2 paragraph map, P5 merge.
 */
internal object ReviewOverlayLoader {

    data class Result(
        val summary: ReviewRuleParser.SummaryResult,
        val session: ReviewOverlaySessionStore.Active?,
        val alignQuality: Double?,
        val providerChapterIndex: Int?,
        val authority: ReviewParagraphAuthority.Kind = ReviewParagraphAuthority.Kind.Unsupported,
        val coverage: Double? = null,
        /**
         * Multi-provider merge payload. Caller must [ReviewOverlaySessionStore.putMerge]
         * only after requestToken / cancel checks succeed — never store from a cancelled load.
         */
        val mergeSession: ReviewOverlaySessionStore.MergeActive? = null,
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

    private data class Aligned(
        val source: BookSource,
        val providerBook: Book,
        val toc: List<BookChapter>,
        val providerChapter: BookChapter,
        val alignQuality: Double,
    )

    suspend fun loadChapterBucket(
        contentBook: Book,
        contentChapter: BookChapter,
        binding: BookReviewBinding,
        storeSession: Boolean = true,
        clearOnFail: Boolean = true,
    ): Result {
        val aligned = alignProvider(contentChapter, binding, clearOnFail) ?: return Result.empty()
        val rawSummary = fetchSummarySafe(aligned.source, aligned.providerBook, aligned.providerChapter)
        return finishBucketOnly(
            contentBook = contentBook,
            contentChapter = contentChapter,
            binding = binding,
            aligned = aligned,
            rawSummary = rawSummary,
            authority = ReviewParagraphAuthority.authorityFor(binding.providerSourceUrl),
            coverage = null,
            coverageLabel = null,
            paraRefs = emptyMap(),
            storeSession = storeSession,
        )
    }

    /**
     * RFC-004 §12: parallel per-binding load; sum -1 counts; P2 only on paragraph_primary.
     */
    suspend fun loadMergedChapterBucket(
        contentBook: Book,
        contentChapter: BookChapter,
        bindings: List<BookReviewBinding>,
        localParas: List<Pair<Int, String>>? = null,
    ): Result = loadMerged(contentBook, contentChapter, bindings, localParas)

    suspend fun loadMerged(
        contentBook: Book,
        contentChapter: BookChapter,
        bindings: List<BookReviewBinding>,
        localParas: List<Pair<Int, String>>? = null,
    ): Result {
        if (bindings.isEmpty()) return Result.empty()
        if (bindings.size == 1) {
            val only = bindings[0]
            return if (localParas != null && localParas.isNotEmpty()) {
                loadWithParagraphMap(contentBook, contentChapter, only, localParas)
            } else {
                loadChapterBucket(contentBook, contentChapter, only)
            }
        }

        val primaryBinding = ReviewOverlayMerge.paragraphPrimary(bindings)
        val perResults = coroutineScope {
            bindings.map { binding ->
                async {
                    try {
                        val isPrimary =
                            binding.providerSourceUrl == primaryBinding?.providerSourceUrl
                        if (isPrimary &&
                            localParas != null &&
                            localParas.isNotEmpty() &&
                            ReviewParagraphAuthority.isParagraphMapOpen(binding.providerSourceUrl) &&
                            AppConfig.reviewOverlayAllowParagraphIcons
                        ) {
                            loadWithParagraphMap(
                                contentBook,
                                contentChapter,
                                binding,
                                localParas,
                                storeSession = false,
                                clearOnFail = false,
                            )
                        } else {
                            loadChapterBucket(
                                contentBook,
                                contentChapter,
                                binding,
                                storeSession = false,
                                clearOnFail = false,
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLog.put(
                            "ReviewOverlay merge skip=${binding.providerSourceUrl}\n" +
                                    "${e.localizedMessage}",
                            e,
                        )
                        null
                    }
                }
            }.awaitAll()
        }
        if (!coroutineContext.isActive) {
            throw CancellationException("ReviewOverlay merge cancelled")
        }

        val providerSessions = ArrayList<ReviewOverlaySessionStore.Active>()
        val bucketCounts = ArrayList<Int>()
        var paraResult: Result? = null
        for ((binding, loaded) in bindings.zip(perResults)) {
            if (loaded == null || loaded.session == null) {
                AppLog.put("ReviewOverlay merge skip=${binding.providerSourceUrl}")
                continue
            }
            providerSessions.add(loaded.session)
            bucketCounts.add(loaded.summary.counts[-1] ?: 0)
            if (binding.providerSourceUrl == primaryBinding?.providerSourceUrl &&
                loaded.summary.counts.keys.any { it > 0 }
            ) {
                paraResult = loaded
            }
        }

        val sum = ReviewOverlayMerge.sumChapterBucketCount(bucketCounts)
        val paragraphPrimary = paraResult?.session
            ?: providerSessions.firstOrNull {
                it.binding.providerSourceUrl == primaryBinding?.providerSourceUrl
            }
            ?: providerSessions.firstOrNull()

        if (providerSessions.isEmpty()) {
            return Result.empty()
        }

        val mergeSession = ReviewOverlaySessionStore.MergeActive(
            contentBookUrl = contentBook.bookUrl,
            contentChapterIndex = contentChapter.index,
            providers = providerSessions.toList(),
            mergedBucketCount = sum,
            paragraphPrimary = paragraphPrimary,
        )

        // Count-only chapter chip: never invent a fake paraData key (RFC A13).
        val displaySummary = mergeChapterDisplaySummary(sum, paraResult?.summary)

        AppLog.put(
            "ReviewOverlay merge providers=${providerSessions.size} " +
                    "bucket=$sum primary=${paragraphPrimary?.binding?.providerSourceUrl}"
        )
        return Result(
            summary = displaySummary,
            session = paragraphPrimary,
            alignQuality = paragraphPrimary?.alignQuality,
            providerChapterIndex = paragraphPrimary?.providerChapterIndex,
            authority = paragraphPrimary?.let {
                ReviewParagraphAuthority.authorityFor(it.binding.providerSourceUrl)
            } ?: ReviewParagraphAuthority.Kind.Unsupported,
            coverage = paraResult?.coverage,
            mergeSession = mergeSession,
        )
    }

    /**
     * P2 path: fetch provider content, hard-map paragraphs, remap summary onto local ids.
     * Falls back to chapter-bucket when authority closed, content missing, or coverage < 0.5.
     */
    suspend fun loadWithParagraphMap(
        contentBook: Book,
        contentChapter: BookChapter,
        binding: BookReviewBinding,
        localParas: List<Pair<Int, String>>,
        storeSession: Boolean = true,
        clearOnFail: Boolean = true,
    ): Result {
        val authority = ReviewParagraphAuthority.authorityFor(binding.providerSourceUrl)
        if (!AppConfig.reviewOverlayAllowParagraphIcons ||
            !ReviewParagraphAuthority.isParagraphMapOpen(authority) ||
            localParas.isEmpty()
        ) {
            return loadChapterBucket(
                contentBook,
                contentChapter,
                binding,
                storeSession = storeSession,
                clearOnFail = clearOnFail,
            )
        }

        val aligned = alignProvider(contentChapter, binding, clearOnFail) ?: return Result.empty()
        val rawSummary = fetchSummarySafe(aligned.source, aligned.providerBook, aligned.providerChapter)

        val providerContent = runCatching {
            WebBook.getContentAwait(
                bookSource = aligned.source,
                book = aligned.providerBook,
                bookChapter = aligned.providerChapter,
                needSave = false,
            )
        }.onFailure {
            AppLog.put(
                "ReviewOverlay bind=${binding.providerSourceUrl} provider content fetch failed\n" +
                        "${it.localizedMessage}",
                it,
            )
        }.getOrNull()

        if (providerContent.isNullOrBlank()) {
            return finishBucketOnly(
                contentBook = contentBook,
                contentChapter = contentChapter,
                binding = binding,
                aligned = aligned,
                rawSummary = rawSummary,
                authority = authority,
                coverage = null,
                coverageLabel = null,
                paraRefs = emptyMap(),
                storeSession = storeSession,
            )
        }

        val providerParas = ReviewParagraphMapper.splitParagraphs(providerContent)
        val mapJob = coroutineContext[Job]
        val localToProvider = ReviewParagraphMapper.hardMapLocalToProvider(
            localParas = localParas,
            providerParas = providerParas,
            isActive = { mapJob?.isActive != false },
        )
        if (mapJob?.isActive == false) {
            throw CancellationException("ReviewOverlay paragraph map cancelled")
        }
        val mappedProviderIndices = localToProvider.values.toSet()
        val coverage = ReviewParagraphMapper.coverage(rawSummary.counts, mappedProviderIndices)
        val coverageLabel = ReviewParagraphMapper.coverageRatioLabel(
            rawSummary.counts,
            mappedProviderIndices,
        )

        if (coverage < ReviewAlignConfig.MAP_COVERAGE_MIN) {
            AppLog.put(
                "ReviewOverlay bind=${binding.providerSourceUrl} align=${aligned.alignQuality} " +
                        "authority=$authority coverage=$coverageLabel (<${ReviewAlignConfig.MAP_COVERAGE_MIN}) " +
                        "→ chapter-bucket only"
            )
            return finishBucketOnly(
                contentBook = contentBook,
                contentChapter = contentChapter,
                binding = binding,
                aligned = aligned,
                rawSummary = rawSummary,
                authority = authority,
                coverage = coverage,
                coverageLabel = coverageLabel,
                paraRefs = emptyMap(),
                storeSession = storeSession,
            )
        }

        val remapped = ReviewParagraphMapper.remapSummaryToLocal(rawSummary, localToProvider)
        val paraRefs = ReviewParagraphMapper.toParaRefs(localToProvider, rawSummary.keys)
        val chapterBucket = bucketRef(remapped)
        val displayCounts = LinkedHashMap<Int, Int>()
        val displayKeys = LinkedHashMap<Int, String>()
        chapterBucket?.let {
            remapped.counts[-1]?.let { c -> displayCounts[-1] = c }
            remapped.keys[-1]?.let { k -> displayKeys[-1] = k }
        }
        for ((localId, ref) in paraRefs) {
            val count = remapped.counts[localId] ?: continue
            displayCounts[localId] = count
            displayKeys[localId] = ref.paraData
        }
        val displaySummary = ReviewRuleParser.SummaryResult(displayCounts, displayKeys)

        val session = ReviewOverlaySessionStore.Active(
            contentBookUrl = contentBook.bookUrl,
            contentChapterIndex = contentChapter.index,
            binding = binding,
            providerSourceKey = aligned.source.getKey(),
            providerBook = aligned.providerBook,
            providerToc = aligned.toc,
            providerChapterIndex = aligned.providerChapter.index,
            providerChapter = aligned.providerChapter,
            alignQuality = aligned.alignQuality,
            chapterBucket = chapterBucket,
            chapterBucketCount = remapped.counts[-1]?.takeIf { it > 0 } ?: 0,
            paraRefs = paraRefs,
        )
        if (storeSession) {
            ReviewOverlaySessionStore.put(session)
        } else {
            ReviewOverlaySessionStore.putProviderBook(session.providerBook)
            ReviewOverlaySessionStore.putProviderToc(session.providerBook.bookUrl, session.providerToc)
        }
        AppLog.put(
            "ReviewOverlay bind=${binding.providerSourceUrl} align=${aligned.alignQuality} " +
                    "authority=$authority coverage=$coverageLabel " +
                    "bucket=${displaySummary.counts[-1] ?: 0} paras=${paraRefs.size}"
        )
        return Result(
            summary = displaySummary,
            session = session,
            alignQuality = aligned.alignQuality,
            providerChapterIndex = aligned.providerChapter.index,
            authority = authority,
            coverage = coverage,
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

    /**
     * Merged chapter-chip display: sum count only — never a fake `merge:N` paraData key (A13).
     */
    fun mergeChapterDisplaySummary(
        sum: Int,
        paragraphPrimary: ReviewRuleParser.SummaryResult? = null,
    ): ReviewRuleParser.SummaryResult {
        val displayCounts = LinkedHashMap<Int, Int>()
        val displayKeys = LinkedHashMap<Int, String>()
        if (sum > 0) {
            displayCounts[-1] = sum
        }
        paragraphPrimary?.let { pr ->
            for ((k, v) in pr.counts) {
                if (k == -1) continue
                displayCounts[k] = v
            }
            for ((k, v) in pr.keys) {
                if (k == -1) continue
                displayKeys[k] = v
            }
        }
        return ReviewRuleParser.SummaryResult(displayCounts, displayKeys)
    }

    private fun finishBucketOnly(
        contentBook: Book,
        contentChapter: BookChapter,
        binding: BookReviewBinding,
        aligned: Aligned,
        rawSummary: ReviewRuleParser.SummaryResult,
        authority: ReviewParagraphAuthority.Kind,
        coverage: Double?,
        coverageLabel: String?,
        paraRefs: Map<Int, ProviderParaRef>,
        storeSession: Boolean = true,
    ): Result {
        val bucketSummary = chapterBucketOnly(rawSummary)
        val chapterBucket = bucketRef(bucketSummary)
        val displaySummary = if (chapterBucket != null) {
            bucketSummary
        } else {
            ReviewRuleParser.SummaryResult(emptyMap(), emptyMap())
        }
        val session = ReviewOverlaySessionStore.Active(
            contentBookUrl = contentBook.bookUrl,
            contentChapterIndex = contentChapter.index,
            binding = binding,
            providerSourceKey = aligned.source.getKey(),
            providerBook = aligned.providerBook,
            providerToc = aligned.toc,
            providerChapterIndex = aligned.providerChapter.index,
            providerChapter = aligned.providerChapter,
            alignQuality = aligned.alignQuality,
            chapterBucket = chapterBucket,
            chapterBucketCount = bucketSummary.counts[-1]?.takeIf { it > 0 } ?: 0,
            paraRefs = paraRefs,
        )
        if (storeSession) {
            ReviewOverlaySessionStore.put(session)
        } else {
            ReviewOverlaySessionStore.putProviderBook(session.providerBook)
            ReviewOverlaySessionStore.putProviderToc(session.providerBook.bookUrl, session.providerToc)
        }
        val coveragePart = coverageLabel?.let { " coverage=$it" }.orEmpty()
        AppLog.put(
            "ReviewOverlay bind=${binding.providerSourceUrl} align=${aligned.alignQuality} " +
                    "authority=$authority$coveragePart bucket=${displaySummary.counts[-1] ?: 0}"
        )
        return Result(
            summary = displaySummary,
            session = session,
            alignQuality = aligned.alignQuality,
            providerChapterIndex = aligned.providerChapter.index,
            authority = authority,
            coverage = coverage,
        )
    }

    private suspend fun alignProvider(
        contentChapter: BookChapter,
        binding: BookReviewBinding,
        clearOnFail: Boolean = true,
    ): Aligned? {
        val source = appDb.bookSourceDao.getBookSource(binding.providerSourceUrl)
        if (source == null || !ReviewCapability.isReviewCapable(source)) {
            if (clearOnFail) ReviewOverlaySessionStore.clearChapter()
            AppLog.put(
                "ReviewOverlay bind=${binding.providerSourceUrl} align=n/a bucket=0 " +
                        "(provider source missing or not review-capable)"
            )
            return null
        }

        val providerBook = ensureProviderBook(source, binding) ?: run {
            if (clearOnFail) ReviewOverlaySessionStore.clearChapter()
            AppLog.put(
                "ReviewOverlay bind=${binding.providerSourceUrl} align=n/a bucket=0 " +
                        "(provider book resolve failed)"
            )
            return null
        }

        val toc = ensureProviderToc(source, providerBook)
        val align = ReviewOverlayMatch.acceptAlign(
            contentChapter.index,
            contentChapter.title,
            toc,
        )
        if (align == null) {
            if (clearOnFail) ReviewOverlaySessionStore.clearChapter()
            AppLog.put(
                "ReviewOverlay bind=${binding.providerSourceUrl} align=fail bucket=0 " +
                        "contentChapter=${contentChapter.index}"
            )
            return null
        }

        val providerChapter = toc.getOrNull(align.index)
        if (providerChapter == null || providerChapter.isVolume) {
            if (clearOnFail) ReviewOverlaySessionStore.clearChapter()
            AppLog.put(
                "ReviewOverlay bind=${binding.providerSourceUrl} align=${align.quality} " +
                        "bucket=0 (provider chapter missing)"
            )
            return null
        }

        return Aligned(
            source = source,
            providerBook = providerBook,
            toc = toc,
            providerChapter = providerChapter,
            alignQuality = align.quality,
        )
    }

    private fun bucketRef(summary: ReviewRuleParser.SummaryResult): ProviderParaRef? {
        val count = summary.counts[-1] ?: return null
        if (count <= 0) return null
        val paraData = summary.keys[-1]?.takeIf { it.isNotBlank() } ?: return null
        return ProviderParaRef(providerParaIndex = -1, paraData = paraData)
    }

    private suspend fun fetchSummarySafe(
        source: BookSource,
        book: Book,
        chapter: BookChapter,
    ): ReviewRuleParser.SummaryResult {
        return runCatching {
            fetchSummary(source, book, chapter)
        }.onFailure {
            AppLog.put(
                "ReviewOverlay bind=${source.getKey()} summary fetch failed\n" +
                        "${it.localizedMessage}",
                it,
            )
        }.getOrNull() ?: ReviewRuleParser.SummaryResult(emptyMap(), emptyMap())
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
