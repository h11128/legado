package io.legado.app.model.checkalgo

import io.legado.app.data.entities.SearchBook
import io.legado.app.help.book.BookAuthorIdentity
import java.net.URLDecoder
import kotlin.math.abs

/**
 * Book-level (整书) change-source quality: latest-title affinity, TOC size,
 * content-first sort, early-stop on enough quality-OK hits.
 *
 * Content digram / stitch / multi-source consensus stay in [ChangeChapterVerify].
 */
object ChangeBookSourceQuality {

    private val bookUrlSourceParam =
        Regex("""(?:^|[?&])source=([^&#]+)""", RegexOption.IGNORE_CASE)

    /** Tips that are UI chrome / placeholders, not chapter titles. */
    private val placeholderLatestTips = setOf(
        "详细", "目录", "正文", "阅读", "查看", "无", "暂无", "无最新章节",
        "连载中", "连载", "完结", "全文", "开始阅读", "点击阅读",
        "最新章节", "继续阅读", "全文阅读", "进入阅读", "立即阅读",
    )

    private val softChapterTips = setOf(
        "序章", "楔子", "番外", "尾声", "前言", "后记", "引子", "感言",
    )

    private val chapterTipHint =
        Regex("第[\\d零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+[章节回集卷部篇话]")

    private val nonBookIntroHints = listOf(
        "该词条未找到",
        "没有找到与此相符",
        "Sorry，没有找到",
        "Sorry,没有找到",
        "词条未找到",
    )

    /**
     * Hosts that are never novel book sources (image / Q&A / dict shells).
     * Defense in depth when search rules fake a title / latest tip.
     * Matched against URL host only (not query/path).
     */
    private val nonNovelHostExact = setOf(
        "image.baidu.com",
        "zhidao.baidu.com",
        "tieba.baidu.com",
        "baike.baidu.com",
        "wenku.baidu.com",
        "dict.cn",
        "fanyi.baidu.com",
    )

    private val nonNovelHostPrefixes = listOf(
        "translate.google.",
    )

    /**
     * Aggregator backends often put `source=` on [SearchBook.bookUrl]
     * (e.g. 晴天 `…/detail?book_id=…&source=69书吧`).
     */
    fun backendFromBookUrl(bookUrl: String?): String? {
        if (bookUrl.isNullOrBlank()) return null
        val encoded = bookUrlSourceParam.find(bookUrl)?.groupValues?.getOrNull(1) ?: return null
        return runCatching {
            URLDecoder.decode(encoded, Charsets.UTF_8.name()).trim()
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Tip text after stripping an aggregator `source=` label prefix.
     * Blank ⇒ search hit has no real latest chapter (e.g. lastChapter=`百度`).
     */
    fun effectiveLatestChapterTitle(latest: String?, bookUrl: String?): String {
        val raw = latest?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        val backend = backendFromBookUrl(bookUrl) ?: return raw
        if (raw.equals(backend, ignoreCase = true)) return ""
        if (raw.length > backend.length &&
            raw.regionMatches(0, backend, 0, backend.length, ignoreCase = true) &&
            raw[backend.length].isWhitespace()
        ) {
            return raw.substring(backend.length).trim()
        }
        return raw
    }

    /**
     * Change-source list needs a tip that looks like a chapter, not chrome / backend label.
     * Bare short labels (「猫眼」「百度」) without `source=` must not count as usable.
     */
    fun hasUsableSearchLatest(latest: String?, bookUrl: String?): Boolean {
        val tip = effectiveLatestChapterTitle(latest, bookUrl)
        if (tip.isEmpty()) return false
        if (tip in placeholderLatestTips) return false
        if (placeholderLatestTips.any { it.length >= 2 && tip.contains(it) && tip.length <= it.length + 2 }) {
            return false
        }
        if (chapterTipHint.containsMatchIn(tip)) return true
        if (tip in softChapterTips) return true
        // Short non-numeric tips are almost always site/backend chrome, not chapter titles.
        if (tip.length <= 6 && tip.none { it.isDigit() }) return false
        return tip.length >= 2
    }

    /**
     * Author overlap gate — only when [requireAuthor] (App 「校验作者」) is on.
     * Empty local author ⇒ no constraint.
     */
    fun authorCompatibleForChangeSource(
        localAuthor: String?,
        hitAuthor: String?,
        requireAuthor: Boolean,
    ): Boolean {
        if (!requireAuthor) return true
        val local = localAuthor?.trim().orEmpty()
        if (local.isEmpty()) return true
        val hit = hitAuthor?.trim().orEmpty()
        if (hit.isEmpty()) return false
        return hit.contains(local) || local.contains(hit)
    }

    fun looksLikeNonBookIntro(intro: String?): Boolean {
        val text = intro?.trim().orEmpty()
        if (text.isEmpty()) return false
        return nonBookIntroHints.any { text.contains(it) }
    }

    /** True when [url] host is a known non-novel shell (百度图片/知道/词典…). */
    fun isNonNovelSearchHost(url: String?): Boolean {
        val host = hostOf(url) ?: return false
        if (host in nonNovelHostExact) return true
        if (nonNovelHostExact.any { host.endsWith(".$it") }) return true
        if (nonNovelHostPrefixes.any { host.startsWith(it) }) return true
        return false
    }

    internal fun hostOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return runCatching {
            val raw = url.trim()
            val withScheme = when {
                raw.startsWith("//") -> "https:$raw"
                "://" in raw -> raw
                else -> "https://$raw"
            }
            java.net.URI(withScheme).host?.lowercase()?.trim('.')
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Empty-latest fallback helper: non-placeholder search author.
     * Overlap with [localAuthor] is only applied when that local author is set —
     * callers decide whether empty-latest needs this signal at all.
     */
    fun hasCredibleAuthorSignal(book: SearchBook, localAuthor: String? = null): Boolean {
        val author = meaningfulAuthor(book.author) ?: return false
        if (looksLikeNonBookIntro(book.intro)) return false
        val local = localAuthor?.trim().orEmpty()
        if (local.isNotEmpty() &&
            !authorCompatibleForChangeSource(local, author, requireAuthor = true)
        ) {
            return false
        }
        return true
    }

    private fun meaningfulAuthor(raw: String?): String? {
        val author = BookAuthorIdentity.effectiveAuthor(raw)
        return author.ifEmpty { null }
    }

    /**
     * Post-search gate for 换源 (beyond exact title match in WebBook filter).
     *
     * Every filter here is a menu toggle — do not hard-force when the user turned it off.
     * Author overlap follows 「校验作者」; non-novel hosts / dictionary intros follow their
     * own items. Empty latest is allowed (title already exact-matched); chrome tips are
     * Host / intro / author gates for display (and deep-probe eligibility).
     * Tip chrome is stripped in [decorateSearchHitForChangeSource].
     */
    fun isAcceptableChangeSourceHit(
        book: SearchBook,
        localAuthor: String?,
        requireAuthor: Boolean = false,
        filterNonNovelHost: Boolean = true,
        filterNonBookIntro: Boolean = true,
    ): Boolean {
        if (filterNonNovelHost &&
            (isNonNovelSearchHost(book.bookUrl) || isNonNovelSearchHost(book.origin))
        ) {
            return false
        }
        if (filterNonBookIntro && looksLikeNonBookIntro(book.intro)) return false
        if (!authorCompatibleForChangeSource(localAuthor, book.author, requireAuthor)) return false
        return true
    }

    fun displayOriginName(originName: String, bookUrl: String?): String {
        val backend = backendFromBookUrl(bookUrl) ?: return originName
        val suffix = " · $backend"
        if (originName.endsWith(suffix)) return originName
        return originName + suffix
    }

    /**
     * Normalize aggregator search hits for the change-source list (chrome tip strip,
     * backend suffix). Does not apply menu filters — those are display-time only.
     */
    fun decorateSearchHitForChangeSource(book: SearchBook) {
        val effective = effectiveLatestChapterTitle(book.latestChapterTitle, book.bookUrl)
        book.latestChapterTitle = when {
            effective.isEmpty() -> null
            hasUsableSearchLatest(effective, null) -> effective
            else -> null
        }
        book.originName = displayOriginName(book.originName, book.bookUrl)
    }

    /**
     * Decorate then apply menu filters.
     * @return false when the hit should be hidden for the current filter prefs.
     * Tip chrome stripping lives in [decorateSearchHitForChangeSource].
     */
    fun prepareSearchHitForChangeSource(
        book: SearchBook,
        localAuthor: String? = null,
        requireAuthor: Boolean = false,
        filterNonNovelHost: Boolean = true,
        filterNonBookIntro: Boolean = true,
    ): Boolean {
        decorateSearchHitForChangeSource(book)
        return isAcceptableChangeSourceHit(
            book,
            localAuthor,
            requireAuthor = requireAuthor,
            filterNonNovelHost = filterNonNovelHost,
            filterNonBookIntro = filterNonBookIntro,
        )
    }


    /** Stop asking more sources once this many quality-OK probes exist. */
    const val EARLY_STOP_QUALITY_OK = 20

    /** Minimum chapterWordCount to count as quality-OK for early-stop. */
    const val QUALITY_OK_MIN_CHARS = 400

    /** Candidate TOC length vs local must stay inside this ratio band. */
    const val TOC_MIN_RATIO = 0.35
    const val TOC_MAX_RATIO = 3.0

    /** Digram Jaccard on latest titles: below this vs local ⇒ mismatch. */
    const val LATEST_REF_SIM_MIN = 0.06

    /**
     * When the probed **chapter body** already matches local ref this strongly,
     * hide 「最新章疑似不一致」— tip titles often lag while dur-chapter text is correct
     * (self-test 2026-08-05: OK rows still showed latest badges).
     */
    const val LATEST_BADGE_SUPPRESS_REF_SIM = 0.50

    /** Peer latest-title cluster edge. */
    const val LATEST_PEER_SIM_MIN = 0.10

    /** Absolute chapter-number gap vs local (when both parse) treated as mismatch. */
    const val LATEST_NUM_GAP = 80

    /** Sort tiers: lower ranks first. */
    const val TIER_OK = 0
    const val TIER_WEAK = 1
    /** Search hit published; content/word-count still loading — keep near top. */
    const val TIER_PENDING = 2
    const val TIER_LATEST_BAD = 3
    const val TIER_TOC_BAD = 4
    const val TIER_CONTENT_BAD = 5
    const val TIER_SOFT_FAIL = 6
    const val TIER_UNKNOWN = 7

    fun shouldEarlyStop(
        qualityOkCount: Int,
        enabled: Boolean,
        target: Int = EARLY_STOP_QUALITY_OK,
    ): Boolean = enabled && qualityOkCount >= target

    fun isQualityOkWordCount(chapterWordCount: Int): Boolean =
        chapterWordCount >= QUALITY_OK_MIN_CHARS

    /** Content-probe verdict for 换源 rows (separate from measured char count). */
    enum class QualityVerdict {
        Pending,
        Ok,
        Weak,
        TooShort,
        AntiTheft,
        Hijack,
        FetchError,
    }

    fun isContentBadVerdict(verdict: QualityVerdict?): Boolean = when (verdict) {
        QualityVerdict.TooShort,
        QualityVerdict.AntiTheft,
        QualityVerdict.Hijack,
        QualityVerdict.FetchError,
        -> true
        else -> false
    }

    fun verdictFromContentQuality(
        quality: ChangeChapterVerify.ContentQuality,
        measuredChars: Int,
    ): QualityVerdict = when (quality) {
        is ChangeChapterVerify.ContentQuality.Ok -> {
            if (isQualityOkWordCount(measuredChars)) QualityVerdict.Ok else QualityVerdict.Weak
        }
        ChangeChapterVerify.ContentQuality.TooShort -> QualityVerdict.TooShort
        ChangeChapterVerify.ContentQuality.AntiTheft -> QualityVerdict.AntiTheft
        ChangeChapterVerify.ContentQuality.Hijack -> QualityVerdict.Hijack
    }

    /** Metric line only — never embed quality labels here. */
    fun metricWordCountText(measuredChars: Int): String = "字数：$measuredChars"

    fun metricLine(measuredChars: Int, respondTimeMs: Int): String {
        val words = metricWordCountText(measuredChars)
        if (respondTimeMs < 0) return words
        val sec = respondTimeMs / 1000.0
        return if (sec < 10) {
            "$words · ${"%.1f".format(sec)}s"
        } else {
            "$words · ${respondTimeMs}ms"
        }
    }

    /**
     * Default-sort key for [smartScore]: ready scores as-is; pending / not-yet-scored
     * floats above content-bad (~5–35) and below typical Ok (~78+).
     */
    const val SMART_SCORE_PENDING_SORT = 60

    fun sortSmartScoreKey(smartScore: Int, verdict: QualityVerdict?): Int = when {
        smartScore >= 0 -> smartScore
        verdict == null || verdict == QualityVerdict.Pending -> SMART_SCORE_PENDING_SORT
        else -> -1
    }

    /**
     * Cached DB rows persist measured [SearchBook.chapterWordCount] but not session
     * [SearchBook.qualityVerdict]. Re-probe when load-word-count is on and verdict is missing.
     */
    fun needsSessionQualityHydration(qualityVerdict: QualityVerdict?, wordCountText: String?): Boolean =
        qualityVerdict == null && !wordCountText.isNullOrBlank()

    /**
     * 0..100 smart score for 换源 list. Pending / unknown → -1 (UI shows —).
     */
    fun smartScore(
        measuredChars: Int,
        verdict: QualityVerdict?,
        contentRefSim: Double? = null,
        latestMismatch: Boolean = false,
        tocMismatch: Boolean = false,
        respondTimeMs: Int = -1,
        userScore: Int = 0,
        expectedChars: Int? = null,
    ): Int {
        val v = verdict ?: return -1
        if (v == QualityVerdict.Pending) return -1
        var score = when (v) {
            QualityVerdict.Ok -> 78
            QualityVerdict.Weak -> 55
            QualityVerdict.TooShort -> 35
            QualityVerdict.AntiTheft -> 15
            QualityVerdict.Hijack -> 10
            QualityVerdict.FetchError -> 5
            QualityVerdict.Pending -> return -1
        }
        if (measuredChars > 0) {
            score += lengthBandScore(measuredChars, expectedChars).let { band ->
                when {
                    band >= measuredChars + 50_000 -> 15
                    band >= measuredChars + 10_000 -> 8
                    isQualityOkWordCount(measuredChars) -> 5
                    measuredChars >= MIN_SCORE_CHARS -> 2
                    else -> 0
                }
            }
        }
        val sim = contentRefSim
        if (sim != null) {
            score += when {
                sim >= 0.50 -> 10
                sim >= 0.20 -> 5
                sim < 0.06 -> -5
                else -> 0
            }
        }
        if (latestMismatch) score -= 6
        if (tocMismatch) score -= 6
        if (respondTimeMs in 0..800) score += 5
        else if (respondTimeMs in 801..2000) score += 2
        else if (respondTimeMs > 8000) score -= 5
        else if (respondTimeMs > 4000) score -= 2
        score += when {
            userScore > 0 -> 8
            userScore < 0 -> -12
            else -> 0
        }
        return score.coerceIn(0, 100)
    }

    private const val MIN_SCORE_CHARS = 80

    /**
     * TOC sizes are consistent enough to be the same book progression.
     * Unknown/zero totals do not punish.
     */
    fun tocConsistent(localTotal: Int, candidateTotal: Int): Boolean {
        if (localTotal <= 0 || candidateTotal <= 0) return true
        val ratio = candidateTotal.toDouble() / localTotal.toDouble()
        return ratio in TOC_MIN_RATIO..TOC_MAX_RATIO
    }

    /**
     * Whether [candidateLatest] looks like the same book tip as [localLatest].
     * Blank sides ⇒ unknown (not a hard fail).
     */
    fun latestMatchesLocal(localLatest: String?, candidateLatest: String?): Boolean? {
        val local = localLatest?.trim().orEmpty()
        val cand = candidateLatest?.trim().orEmpty()
        if (local.isEmpty() || cand.isEmpty()) return null
        val localKey = ChangeChapterVerify.parseProbeKey(ChangeChapterVerify.chapterKey(0, local))
        val candKey = ChangeChapterVerify.parseProbeKey(ChangeChapterVerify.chapterKey(0, cand))
        val localBody = titleBodyAfterChapterNum(local)
        val candBody = titleBodyAfterChapterNum(cand)
        if (localBody.isNotEmpty() && localBody == candBody) return true
        if (localKey.num > 0 && candKey.num > 0) {
            if (abs(localKey.num - candKey.num) >= LATEST_NUM_GAP) return false
            if (localKey.num == candKey.num) {
                // Same 「第N章」prefix must not count as a match — compare title bodies only.
                if (localBody.isEmpty() || candBody.isEmpty()) return false
                return ChangeChapterVerify.digramJaccard(localBody, candBody) >= LATEST_REF_SIM_MIN
            }
        }
        if (localBody.isNotEmpty() && candBody.isNotEmpty()) {
            return ChangeChapterVerify.digramJaccard(localBody, candBody) >= LATEST_REF_SIM_MIN
        }
        return ChangeChapterVerify.digramJaccard(local, cand) >= LATEST_REF_SIM_MIN
    }

    /** Drop leading 「第N章/回/…」 so shared chapter numbers do not inflate digram scores. */
    internal fun titleBodyAfterChapterNum(title: String): String {
        val stripped = title.replace(chapterPrefix, "").trim()
        return stripped.ifEmpty { title.trim() }
    }

    private val chapterPrefix =
        Regex("^.*?第[\\d零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+[章节篇回集话]")

    /**
     * Origins whose latest title disagrees with a trustworthy cluster / local tip.
     * Same safety idea as content consensus: do not demote a coherent minority when
     * the majority looks like shared spam unless local tip confirms the majority.
     */
    fun latestTitleOutliers(
        titlesByOrigin: Map<String, String>,
        localLatest: String? = null,
        minSamples: Int = ChangeChapterVerify.MULTI_SOURCE_MIN_SAMPLES,
    ): Set<String> {
        if (titlesByOrigin.size < minSamples) {
            return titlesByOrigin.mapNotNull { (origin, title) ->
                origin.takeIf { latestMatchesLocal(localLatest, title) == false }
            }.toSet()
        }
        val origins = titlesByOrigin.keys.toList()
        val clusters = ChangeChapterVerify.connectedClusters(origins) { a, b ->
            ChangeChapterVerify.digramJaccard(
                titlesByOrigin.getValue(a),
                titlesByOrigin.getValue(b),
            ) >= LATEST_PEER_SIM_MIN
        }.filter { it.size >= 2 }
        if (clusters.isEmpty()) {
            return titlesByOrigin.mapNotNull { (origin, title) ->
                origin.takeIf { latestMatchesLocal(localLatest, title) == false }
            }.toSet()
        }

        val local = localLatest?.trim()?.takeIf { it.isNotEmpty() }
        val scored = clusters.map { members ->
            val texts = members.map { titlesByOrigin.getValue(it) }
            val avgLocal = local?.let { tip ->
                texts.map { ChangeChapterVerify.digramJaccard(it, tip) }.average()
            }
            val score = if (local != null) {
                (avgLocal ?: 0.0) * 20.0 + members.size
            } else {
                members.size.toDouble()
            }
            Triple(members, score, avgLocal)
        }
        val best = scored.maxByOrNull { it.second } ?: return emptySet()
        if (local != null && (best.third ?: 0.0) < LATEST_REF_SIM_MIN) {
            return titlesByOrigin.mapNotNull { (origin, title) ->
                origin.takeIf { latestMatchesLocal(local, title) == false }
            }.toSet()
        }
        val auth = best.first.toSet()
        val authTitles = best.first.map { titlesByOrigin.getValue(it) }
        val outliers = LinkedHashSet<String>()
        for (origin in origins) {
            if (origin in auth) continue
            val title = titlesByOrigin.getValue(origin)
            if (local != null && latestMatchesLocal(local, title) == true) continue
            val maxSim = authTitles.maxOf { ChangeChapterVerify.digramJaccard(title, it) }
            if (maxSim < LATEST_PEER_SIM_MIN) {
                outliers.add(origin)
            }
        }
        return outliers
    }

    /** Merge tier codes; worse (higher) wins. */
    fun worseTier(a: Int, b: Int): Int = maxOf(a, b)

    /**
     * Hard sort rank from **content probe only**.
     * Prefer [verdict] when set; otherwise fall back to measured [chapterWordCount]
     * (legacy rows / pending).
     */
    fun contentSortTier(
        chapterWordCount: Int,
        wordCountText: String? = null,
        softFailed: Boolean = false,
        verdict: QualityVerdict? = null,
    ): Int {
        val contentTier = when (verdict) {
            QualityVerdict.Pending -> TIER_PENDING
            QualityVerdict.Ok -> TIER_OK
            QualityVerdict.Weak -> TIER_WEAK
            QualityVerdict.TooShort,
            QualityVerdict.AntiTheft,
            QualityVerdict.Hijack,
            QualityVerdict.FetchError,
            -> TIER_CONTENT_BAD
            null -> when {
                chapterWordCount >= QUALITY_OK_MIN_CHARS -> TIER_OK
                chapterWordCount > 0 -> TIER_WEAK
                chapterWordCount == 0 -> TIER_PENDING
                chapterWordCount == -1 && !wordCountText.isNullOrBlank() -> TIER_CONTENT_BAD
                else -> TIER_UNKNOWN
            }
        }
        // Content-first: session soft-fail must not bury probes that already got OK/WEAK body.
        if (contentTier == TIER_OK || contentTier == TIER_WEAK) return contentTier
        // Pending search hits stay visible — do not demote them to soft-fail while loading.
        if (contentTier == TIER_PENDING) return TIER_PENDING
        return if (softFailed) worseTier(contentTier, TIER_SOFT_FAIL) else contentTier
    }

    /**
     * Light penalty for latest/TOC mismatch badges. Applied after content + respondTime.
     * Quality-OK bodies skip the penalty — tip mismatch is noise once chapter text matched.
     * Pending / content-bad also skip — failure text is enough; meta tier must not resort noise.
     */
    fun softMetaPenalty(
        metaTiers: Int,
        chapterWordCount: Int = Int.MIN_VALUE,
        verdict: QualityVerdict? = null,
    ): Int {
        if (isContentBadVerdict(verdict)) return 0
        if (chapterWordCount != Int.MIN_VALUE) {
            if (chapterWordCount <= 0 || isQualityOkWordCount(chapterWordCount)) return 0
        }
        return when (metaTiers) {
            TIER_LATEST_BAD, TIER_TOC_BAD -> 1
            else -> 0
        }
    }

    /** Soft tip/TOC badge kind — never used to hide rows, only display. */
    enum class SoftMetaKind { LATEST, TOC }

    /**
     * Soft meta badges (latest tip / TOC size) for list rows — never hard-filter.
     * - Content-bad verdict / pending / unmeasured (`chapterWordCount <= 0`): never.
     * - TOC: only weak body (`0 < words < QUALITY_OK_MIN_CHARS`) when local ruler trusted;
     *   never on quality-OK and never when untrusted (official vs pirate counts diverge).
     * - Latest + quality-OK: show when body refSim is weak; when refSim is null, only if
     *   local body is untrusted (tip is the remaining wrong-book signal).
     * - Latest + weak body: always show when tip/TOC gate already matched upstream.
     */
    fun shouldShowSoftMetaBadge(
        chapterWordCount: Int,
        contentRefSim: Double?,
        referenceTrusted: Boolean = true,
        kind: SoftMetaKind = SoftMetaKind.LATEST,
        verdict: QualityVerdict? = null,
    ): Boolean {
        if (isContentBadVerdict(verdict) || verdict == QualityVerdict.Pending) return false
        if (chapterWordCount <= 0) return false
        if (kind == SoftMetaKind.TOC && !referenceTrusted) return false
        if (!isQualityOkWordCount(chapterWordCount)) {
            // Weak body only — TOC already gated on trusted above.
            return true
        }
        // Quality-OK: TOC never; latest uses refSim / untrusted-null rule.
        if (kind == SoftMetaKind.TOC) return false
        val sim = contentRefSim
        if (sim != null) return sim < LATEST_BADGE_SUPPRESS_REF_SIM
        return !referenceTrusted
    }

    fun shouldShowLatestMismatchBadge(
        chapterWordCount: Int,
        contentRefSim: Double?,
        referenceTrusted: Boolean = true,
        verdict: QualityVerdict? = null,
    ): Boolean = shouldShowSoftMetaBadge(
        chapterWordCount = chapterWordCount,
        contentRefSim = contentRefSim,
        referenceTrusted = referenceTrusted,
        kind = SoftMetaKind.LATEST,
        verdict = verdict,
    )

    fun shouldShowTocMismatchBadge(
        chapterWordCount: Int,
        contentRefSim: Double?,
        referenceTrusted: Boolean = true,
        verdict: QualityVerdict? = null,
    ): Boolean = shouldShowSoftMetaBadge(
        chapterWordCount = chapterWordCount,
        contentRefSim = contentRefSim,
        referenceTrusted = referenceTrusted,
        kind = SoftMetaKind.TOC,
        verdict = verdict,
    )

    /**
     * Probe respondTime for result-list sort: unknown/negative sink within the same
     * content tier (does not outrank content quality).
     */
    fun respondTimeSortKey(respondTimeMs: Int): Int =
        if (respondTimeMs < 0) Int.MAX_VALUE else respondTimeMs

    /**
     * Legacy helper: OK/WEAK content ignores latest/TOC meta hard-merge.
     */
    fun sortTier(
        chapterWordCount: Int,
        wordCountText: String? = null,
        metaTiers: Int = TIER_UNKNOWN,
        softFailed: Boolean = false,
    ): Int {
        val content = contentSortTier(chapterWordCount, wordCountText, softFailed)
        if (content == TIER_OK || content == TIER_WEAK) return content
        if (metaTiers == TIER_UNKNOWN) return content
        if (content == TIER_UNKNOWN) return metaTiers
        return worseTier(content, metaTiers)
    }

    /**
     * Prefer lengths near [expectedChars] over "longer is always better".
     * Higher score sorts first.
     */
    fun lengthBandScore(chapterWordCount: Int, expectedChars: Int?): Int {
        if (chapterWordCount <= 0) return Int.MIN_VALUE / 4
        val expected = expectedChars?.takeIf { it >= ChangeChapterVerify.MIN_CONTENT_CHARS }
            ?: return chapterWordCount
        val ratio = chapterWordCount.toDouble() / expected.toDouble()
        return when {
            ratio in 0.7..1.4 -> chapterWordCount + 50_000
            ratio in 0.4..2.0 -> chapterWordCount + 10_000
            else -> chapterWordCount
        }
    }
}
