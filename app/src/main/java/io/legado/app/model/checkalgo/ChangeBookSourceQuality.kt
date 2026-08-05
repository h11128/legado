package io.legado.app.model.checkalgo

import kotlin.math.abs

/**
 * Book-level (整书) change-source quality: latest-title affinity, TOC size,
 * content-first sort, early-stop on enough quality-OK hits.
 *
 * Content digram / stitch / multi-source consensus stay in [ChangeChapterVerify].
 */
object ChangeBookSourceQuality {

    /** Stop asking more sources once this many quality-OK probes exist. */
    const val EARLY_STOP_QUALITY_OK = 20

    /** Minimum chapterWordCount to count as quality-OK for early-stop. */
    const val QUALITY_OK_MIN_CHARS = 1000

    /** Candidate TOC length vs local must stay inside this ratio band. */
    const val TOC_MIN_RATIO = 0.35
    const val TOC_MAX_RATIO = 3.0

    /** Digram Jaccard on latest titles: below this vs local ⇒ mismatch. */
    const val LATEST_REF_SIM_MIN = 0.06

    /** Peer latest-title cluster edge. */
    const val LATEST_PEER_SIM_MIN = 0.10

    /** Absolute chapter-number gap vs local (when both parse) treated as mismatch. */
    const val LATEST_NUM_GAP = 80

    /** Sort tiers: lower ranks first. */
    const val TIER_OK = 0
    const val TIER_WEAK = 1
    const val TIER_LATEST_BAD = 2
    const val TIER_TOC_BAD = 3
    const val TIER_CONTENT_BAD = 4
    const val TIER_SOFT_FAIL = 5
    const val TIER_UNKNOWN = 6

    fun shouldEarlyStop(
        qualityOkCount: Int,
        enabled: Boolean,
        target: Int = EARLY_STOP_QUALITY_OK,
    ): Boolean = enabled && qualityOkCount >= target

    fun isQualityOkWordCount(chapterWordCount: Int): Boolean =
        chapterWordCount >= QUALITY_OK_MIN_CHARS

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
     * Latest-chapter / TOC meta must not veto a body that already passed content gates —
     * those stay as [softMetaPenalty] after respondTime.
     */
    fun contentSortTier(
        chapterWordCount: Int,
        wordCountText: String? = null,
        softFailed: Boolean = false,
    ): Int {
        val contentTier = when {
            chapterWordCount >= QUALITY_OK_MIN_CHARS -> TIER_OK
            chapterWordCount > 0 -> TIER_WEAK
            chapterWordCount == -1 && !wordCountText.isNullOrBlank() -> TIER_CONTENT_BAD
            else -> TIER_UNKNOWN
        }
        // Content-first: session soft-fail must not bury probes that already got OK/WEAK body.
        if (contentTier == TIER_OK || contentTier == TIER_WEAK) return contentTier
        return if (softFailed) worseTier(contentTier, TIER_SOFT_FAIL) else contentTier
    }

    /**
     * Light penalty for latest/TOC mismatch badges. Applied after content + respondTime.
     */
    fun softMetaPenalty(metaTiers: Int): Int = when (metaTiers) {
        TIER_LATEST_BAD, TIER_TOC_BAD -> 1
        else -> 0
    }

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
