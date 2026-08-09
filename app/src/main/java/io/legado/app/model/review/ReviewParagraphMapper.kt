package io.legado.app.model.review

import io.legado.app.model.analyzeRule.ReviewRuleParser
import io.legado.app.model.checkalgo.ChangeChapterVerify

/**
 * RFC-004 §6.5 — pure paragraph hard-map (Android-free).
 *
 * Split for mapping uses all non-empty `\n+` blocks (no stitch min-length filter).
 * Local ids are 1-based body paragraph ids matching ChapterProvider review space.
 */
object ReviewParagraphMapper {

    private val PARA_SPLIT = Regex("\\n+")

    fun splitParagraphs(content: String): List<String> =
        content.split(PARA_SPLIT).map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * Greedy 1:1 hard-map: digramJaccard ≥ [minSim], never overwrite an occupied local.
     * @param localParas pairs of (ChapterProvider reviewId, text) — reviewId must already
     *   be `paragraphNum - titleOffset` (never invent index+1).
     * @return Map reviewId → providerParaIndex (1-based body indices on provider side)
     */
    fun hardMapLocalToProvider(
        localParas: List<Pair<Int, String>>,
        providerParas: List<String>,
        minSim: Double = ReviewAlignConfig.PARA_MAP_MIN,
        maxParas: Int = ReviewAlignConfig.PARA_MAP_MAX,
        isActive: () -> Boolean = { true },
    ): Map<Int, Int> {
        val locals = localParas.take(maxParas.coerceAtLeast(0))
        val providers = providerParas.take(maxParas.coerceAtLeast(0))
        if (locals.isEmpty() || providers.isEmpty()) return emptyMap()

        data class Cand(val providerIdx: Int, val localPos: Int, val sim: Double)

        val cands = ArrayList<Cand>(8)
        for (pi in providers.indices) {
            if (!isActive()) return emptyMap()
            for (li in locals.indices) {
                val sim = ChangeChapterVerify.digramJaccard(providers[pi], locals[li].second)
                if (sim >= minSim) {
                    cands.add(Cand(pi, li, sim))
                }
            }
        }
        cands.sortByDescending { it.sim }

        val usedProvider = BooleanArray(providers.size)
        val usedLocal = BooleanArray(locals.size)
        val result = LinkedHashMap<Int, Int>()
        for (c in cands) {
            if (!isActive()) return emptyMap()
            if (usedProvider[c.providerIdx] || usedLocal[c.localPos]) continue
            usedProvider[c.providerIdx] = true
            usedLocal[c.localPos] = true
            val reviewId = locals[c.localPos].first
            if (reviewId > 0) {
                result[reviewId] = c.providerIdx + 1
            }
        }
        return result
    }

    /** Convenience for tests with 1-based sequential body ids. */
    fun hardMapLocalToProvider(
        localTexts: List<String>,
        providerParas: List<String>,
        minSim: Double = ReviewAlignConfig.PARA_MAP_MIN,
        maxParas: Int = ReviewAlignConfig.PARA_MAP_MAX,
        startReviewId: Int = 1,
        isActive: () -> Boolean = { true },
    ): Map<Int, Int> {
        val pairs = localTexts.mapIndexed { i, text -> (startReviewId + i) to text }
        return hardMapLocalToProvider(pairs, providerParas, minSim, maxParas, isActive)
    }

    /**
     * §6.5.3 coverage over reviewed body entries (count > 0, paraIndex > 0).
     * Soft maps do not exist — only hard-mapped provider indices count.
     */
    fun coverage(
        summaryCounts: Map<Int, Int>,
        hardMappedProviderIndices: Set<Int>,
    ): Double {
        val reviewed = summaryCounts.filter { (idx, count) -> idx > 0 && count > 0 }.keys
        if (reviewed.isEmpty()) return 1.0
        val mapped = reviewed.count { it in hardMappedProviderIndices }
        return mapped.toDouble() / reviewed.size
    }

    fun coverageRatioLabel(
        summaryCounts: Map<Int, Int>,
        hardMappedProviderIndices: Set<Int>,
    ): String {
        val reviewed = summaryCounts.filter { (idx, count) -> idx > 0 && count > 0 }.keys
        val mapped = reviewed.count { it in hardMappedProviderIndices }
        return "$mapped/${reviewed.size}"
    }

    /**
     * Remap provider summary counts/keys onto local para ids via hard-map.
     * Keeps chapter bucket (-1) when present with count > 0.
     */
    internal fun remapSummaryToLocal(
        raw: ReviewRuleParser.SummaryResult,
        localToProvider: Map<Int, Int>,
        includeChapterBucket: Boolean = true,
    ): ReviewRuleParser.SummaryResult {
        val counts = LinkedHashMap<Int, Int>()
        val keys = LinkedHashMap<Int, String>()
        if (includeChapterBucket) {
            raw.counts[-1]?.takeIf { it > 0 }?.let { counts[-1] = it }
            raw.keys[-1]?.takeIf { it.isNotBlank() }?.let { keys[-1] = it }
        }
        for ((localId, providerIdx) in localToProvider) {
            val count = raw.counts[providerIdx]?.takeIf { it > 0 } ?: continue
            val paraData = raw.keys[providerIdx]?.takeIf { it.isNotBlank() } ?: continue
            counts[localId] = count
            keys[localId] = paraData
        }
        return ReviewRuleParser.SummaryResult(counts, keys)
    }

    fun toParaRefs(
        localToProvider: Map<Int, Int>,
        summaryKeys: Map<Int, String>,
    ): Map<Int, ProviderParaRef> {
        val refs = LinkedHashMap<Int, ProviderParaRef>()
        for ((localId, providerIdx) in localToProvider) {
            val paraData = summaryKeys[providerIdx]?.takeIf { it.isNotBlank() } ?: continue
            refs[localId] = ProviderParaRef(
                providerParaIndex = providerIdx,
                paraData = paraData,
            )
        }
        return refs
    }
}
