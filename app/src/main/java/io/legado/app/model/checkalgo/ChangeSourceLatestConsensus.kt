package io.legado.app.model.checkalgo

import kotlin.math.abs

/**
 * Latest-chapter identity for 换源.
 *
 * Primary: cluster peer tips. Different update progress is not a mismatch.
 * Auxiliary: pairwise [tipsAgree] — same chapter number + unlike title body.
 * [localLatest] is one extra vote, never the ruler.
 */
object ChangeSourceLatestConsensus {

    const val BODY_SIM_MIN = 0.06
    const val NUM_GAP = 80
    const val MIN_FACTION = 2

    private val chapterPrefix =
        Regex("^.*?第[\\d零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+[章节篇回集话]")

    data class ParsedTip(val raw: String, val num: Int, val body: String)

    fun parseTip(raw: String?): ParsedTip? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val key = ChangeChapterVerify.parseProbeKey(ChangeChapterVerify.chapterKey(0, text))
        return ParsedTip(raw = text, num = key.num, body = titleBodyAfterChapterNum(text))
    }

    fun titleBodyAfterChapterNum(title: String, fallbackToFull: Boolean = true): String {
        val stripped = title.replace(chapterPrefix, "").trim()
        if (stripped.isNotEmpty()) return stripped
        return if (fallbackToFull) title.trim() else ""
    }

    /**
     * Auxiliary pairwise rule.
     * Different chapter numbers → unknown (progress, not identity).
     * Same number → compare title bodies only.
     */
    fun tipsAgree(left: String?, right: String?): Boolean? {
        val a = parseTip(left) ?: return null
        val b = parseTip(right) ?: return null
        if (a.body.isNotEmpty() && a.body == b.body) return true
        if (a.num > 0 && b.num > 0) {
            if (a.num != b.num) return null
            if (a.body.isEmpty() || b.body.isEmpty()) return false
            return ChangeChapterVerify.digramJaccard(a.body, b.body) >= BODY_SIM_MIN
        }
        if (a.body.isNotEmpty() && b.body.isNotEmpty()) {
            return ChangeChapterVerify.digramJaccard(a.body, b.body) >= BODY_SIM_MIN
        }
        return ChangeChapterVerify.digramJaccard(a.raw, b.raw) >= BODY_SIM_MIN
    }

    /**
     * Primary path: origins whose tip is an identity outlier vs the peer cluster.
     * Ahead/behind in chapter number is not an outlier when a real faction exists.
     */
    fun identityOutliers(
        titlesByOrigin: Map<String, String>,
        localLatest: String? = null,
        minSamples: Int = ChangeChapterVerify.MULTI_SOURCE_MIN_SAMPLES,
    ): Set<String> {
        if (titlesByOrigin.size < minSamples) return emptySet()
        val parsed = titlesByOrigin.mapNotNull { (origin, title) ->
            parseTip(title)?.let { origin to it }
        }.toMap()
        if (parsed.size < minSamples) return emptySet()
        val origins = parsed.keys.toList()
        val numbered = origins.filter { parsed.getValue(it).num > 0 }
        val progressGroups = ChangeChapterVerify.connectedClusters(numbered) { a, b ->
            abs(parsed.getValue(a).num - parsed.getValue(b).num) < NUM_GAP
        }
        val dominant = pickDominantProgress(progressGroups, parsed) ?: return emptySet()
        val dominantMedian = medianNum(dominant.map { parsed.getValue(it).num })
        val identityGroups = ChangeChapterVerify.connectedClusters(origins) { a, b ->
            tipsAgree(parsed.getValue(a).raw, parsed.getValue(b).raw) == true
        }.filter { it.size >= MIN_FACTION }
        val auth = pickAuthIdentity(identityGroups, dominant, parsed, localLatest)
        val outliers = LinkedHashSet<String>()
        for (origin in origins) {
            if (isIdentityOutlier(origin, parsed, dominantMedian, auth)) {
                outliers.add(origin)
            }
        }
        return outliers
    }

    private fun pickDominantProgress(
        groups: List<List<String>>,
        parsed: Map<String, ParsedTip>,
    ): List<String>? {
        if (groups.isEmpty()) return null
        return groups.maxWithOrNull(
            compareBy<List<String>> { it.size }
                .thenBy { medianNum(it.map { origin -> parsed.getValue(origin).num }) },
        )
    }

    private fun pickAuthIdentity(
        groups: List<List<String>>,
        dominant: List<String>,
        parsed: Map<String, ParsedTip>,
        localLatest: String?,
    ): Set<String> {
        if (groups.isEmpty()) return emptySet()
        val dominantSet = dominant.toSet()
        val overlapping = groups.filter { group -> group.any { it in dominantSet } }
        val pool = overlapping.ifEmpty { groups }
        val local = localLatest?.trim()?.takeIf { it.isNotEmpty() }
        return pool.maxByOrNull { group ->
            val localBoost = if (local != null &&
                group.any { tipsAgree(local, parsed.getValue(it).raw) == true }
            ) 1 else 0
            group.size + localBoost
        }?.toSet().orEmpty()
    }

    private fun isIdentityOutlier(
        origin: String,
        parsed: Map<String, ParsedTip>,
        dominantMedian: Int,
        auth: Set<String>,
    ): Boolean {
        if (origin in auth) return false
        val tip = parsed.getValue(origin)
        val authTips = auth.map { parsed.getValue(it) }
        if (tip.num <= 0) {
            if (authTips.any { tipsAgree(tip.raw, it.raw) == true }) return false
            return auth.isNotEmpty()
        }
        if (authTips.any { sameBookProgress(tip, it) }) return false
        val inWindow = abs(tip.num - dominantMedian) < NUM_GAP
        if (inWindow) {
            if (authTips.any { tipsAgree(tip.raw, it.raw) == true }) return false
            return authTips.any { it.num == tip.num && tipsAgree(tip.raw, it.raw) == false }
        }
        val ownSize = parsed.values.count { it.num > 0 && abs(it.num - tip.num) < NUM_GAP }
        if (ownSize >= MIN_FACTION) return false
        if (tip.num > dominantMedian) return false
        return abs(tip.num - dominantMedian) >= NUM_GAP
    }

    /** Same title body at another chapter number = progress, not identity fail. */
    private fun sameBookProgress(tip: ParsedTip, other: ParsedTip): Boolean {
        if (tip.body.isEmpty() || other.body.isEmpty()) return false
        if (tip.body == other.body) return true
        return ChangeChapterVerify.digramJaccard(tip.body, other.body) >= BODY_SIM_MIN
    }

    private fun medianNum(nums: List<Int>): Int {
        if (nums.isEmpty()) return 0
        val sorted = nums.sorted()
        return sorted[sorted.size / 2]
    }
}
