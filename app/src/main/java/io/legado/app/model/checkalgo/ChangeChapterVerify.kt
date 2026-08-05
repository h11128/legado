package io.legado.app.model.checkalgo

import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.ChangeSourceChapterProbe
import io.legado.app.data.entities.SearchBook
import kotlin.math.max
import kotlin.math.min

/**
 * Pure helpers for chapter-scoped change-source verify / ranking / scheduling.
 * Kept Android-free so unit tests can run on the JVM.
 */
object ChangeChapterVerify {

    const val TOP_K_CONTENT = 8
    /** Max candidates to TOC-align in one verify pass (priority queue head). */
    const val TOC_ALIGN_CAP = 24
    /** Stop fetching more TOC once this many usable alignments exist (ok + toc_ok). */
    const val TOC_OK_TARGET = 12
    /**
     * Stop content probes once this many *quality* STATUS_OK exist among current candidates.
     * Default = [TOP_K_CONTENT]: probe the full Top-K unless cache already filled enough OKs.
     * (Do not use a tiny number like 2 — short/anti-theft pages would starve the list.)
     */
    const val CONTENT_OK_EARLY_STOP = TOP_K_CONTENT
    /** Parallelism for content probes (host bucket still paces per host). */
    const val CONTENT_PARALLEL = 4
    /** Below this, treat fetched body as not a real chapter (absolute floor). */
    const val MIN_CONTENT_CHARS = 120
    /** Relative length: fail when below this fraction of expected chapter size. */
    const val RELATIVE_MIN_RATIO = 0.22
    /** Only apply relative check when expected size is at least this. */
    const val RELATIVE_MIN_EXPECTED = 400
    /** Reference chapter must be at least this long to run similarity. */
    const val REFERENCE_MIN_CHARS = 200
    /**
     * Digram Jaccard below this vs a known-good chapter ⇒ wrong/hijacked text.
     * Optional boost when local cache exists; not required for primary detection.
     */
    const val REFERENCE_SIM_HIJACK_MAX = 0.04
    /** Paragraph must be at least this long to join stitch detection. */
    const val STITCH_PARA_MIN_CHARS = 20
    /** Need at least this many long paragraphs. */
    const val STITCH_PARA_MIN_COUNT = 3
    /** Consecutive paragraphs below this digram Jaccard count as a "cut". */
    const val STITCH_PARA_SIM_MAX = 0.03
    /** Fraction of consecutive cuts that marks stitched multi-novel spam. */
    const val STITCH_CUT_RATIO = 0.5
    /** Multi-source: need this many body samples before consensus. */
    const val MULTI_SOURCE_MIN_SAMPLES = 3
    /** Multi-source: edge threshold for clustering agreeing bodies. */
    const val MULTI_SOURCE_CLUSTER_MIN_SIM = 0.10
    /** Multi-source: max similarity to authority cluster below this ⇒ candidate outlier. */
    const val MULTI_SOURCE_OUTLIER_MAX_SIM = 0.045
    /**
     * When a local reference chapter exists, an authority cluster must average at least
     * this digram Jaccard vs it — blocks “majority same anti-theft” from becoming authority.
     */
    const val MULTI_SOURCE_AUTH_REF_MIN = 0.12

    data class ContentEvalContext(
        /** Typical chapter length from peer OK probes / current chapter; null = skip relative. */
        val expectedChars: Int? = null,
        /** Optional known-good chapter body; boost only, not required. */
        val referenceContent: String? = null,
    )

    /**
     * Ultra high-precision shells that almost never appear in real novel prose.
     * Kept tiny on purpose — do not grow into a rotating keyword farm.
     */
    private val highPrecisionShellMarkers = listOf(
        "您现在看的是防盗",
        "正确章节请访问",
        "天才一秒记住",
        "一秒记住本站",
        "请记住本站域名",
    )

    data class AlignResult(val index: Int, val quality: Double)

    sealed class ContentQuality {
        data class Ok(val length: Int) : ContentQuality()
        data object TooShort : ContentQuality()
        data object AntiTheft : ContentQuality()
        /** Wrong book / stitched multi-novel / multi-source outlier. */
        data object Hijack : ContentQuality()
    }

    /**
     * Decide whether fetched chapter text counts as a real readable chapter.
     *
     * Structural order (no rotating title lists):
     * 1) absolute / relative length
     * 2) intra-document stitch: consecutive paragraphs look like different novels
     * 3) optional similarity to local reference chapter
     * 4) tiny high-precision shell markers
     *
     * Cross-source consensus is applied separately via [multiSourceOutlierOrigins].
     */
    fun evaluateContent(
        content: String,
        context: ContentEvalContext = ContentEvalContext(),
    ): ContentQuality = evaluateContentDiag(content, context).quality

    /**
     * Same gates as [evaluateContent] plus fields for 换源 forensics / logcat.
     */
    data class ContentEvalDiag(
        val quality: ContentQuality,
        val contentLen: Int,
        val stitch: Boolean,
        val refSim: Double?,
        val expectedChars: Int?,
        val reason: String,
    )

    fun evaluateContentDiag(
        content: String,
        context: ContentEvalContext = ContentEvalContext(),
    ): ContentEvalDiag {
        val text = content.trim()
        val expected = context.expectedChars
        if (text.length < MIN_CONTENT_CHARS) {
            return ContentEvalDiag(
                quality = ContentQuality.TooShort,
                contentLen = text.length,
                stitch = false,
                refSim = null,
                expectedChars = expected,
                reason = "too_short_abs",
            )
        }
        if (expected != null && expected >= RELATIVE_MIN_EXPECTED) {
            val floor = max(MIN_CONTENT_CHARS, (expected * RELATIVE_MIN_RATIO).toInt())
            if (text.length < floor) {
                return ContentEvalDiag(
                    quality = ContentQuality.TooShort,
                    contentLen = text.length,
                    stitch = false,
                    refSim = null,
                    expectedChars = expected,
                    reason = "too_short_rel(floor=$floor)",
                )
            }
        }
        val stitch = looksLikeStitchedParagraphs(text)
        val reference = context.referenceContent?.trim().orEmpty()
        val refSim = if (reference.length >= REFERENCE_MIN_CHARS && text.length >= MIN_CONTENT_CHARS) {
            digramJaccard(text, reference)
        } else {
            null
        }
        // Reference disagreement is the hard wrong-book signal.
        if (refSim != null && refSim < REFERENCE_SIM_HIJACK_MAX) {
            return ContentEvalDiag(
                quality = ContentQuality.Hijack,
                contentLen = text.length,
                stitch = stitch,
                refSim = refSim,
                expectedChars = expected,
                reason = "ref_sim",
            )
        }
        // Stitch alone often false-positives on dialogue/scene breaks.
        // Only hard-fail when there is no *strong* reference agreement (>= AUTH_REF_MIN).
        if (stitch && (refSim == null || refSim < MULTI_SOURCE_AUTH_REF_MIN)) {
            return ContentEvalDiag(
                quality = ContentQuality.Hijack,
                contentLen = text.length,
                stitch = true,
                refSim = refSim,
                expectedChars = expected,
                reason = if (refSim == null) "stitch" else "stitch_weak_ref",
            )
        }
        if (highPrecisionShellMarkers.any { text.contains(it) } &&
            text.length < RELATIVE_MIN_EXPECTED * 2
        ) {
            return ContentEvalDiag(
                quality = ContentQuality.AntiTheft,
                contentLen = text.length,
                stitch = stitch,
                refSim = refSim,
                expectedChars = expected,
                reason = "shell",
            )
        }
        return ContentEvalDiag(
            quality = ContentQuality.Ok(text.length),
            contentLen = text.length,
            stitch = stitch,
            refSim = refSim,
            expectedChars = expected,
            reason = if (stitch) "ok_stitch_override" else "ok",
        )
    }

    /**
     * Anti-scrape / mixed-ad pattern: each block is cut from a different novel
     * (little shared diction across paragraphs — different casts / settings).
     *
     * Uses isolation vs the rest of the document, not only consecutive pairs,
     * so a normal chapter with scene breaks is less likely to false-positive.
     */
    fun looksLikeStitchedParagraphs(content: String): Boolean {
        val paragraphs = content
            .split(Regex("\\n+"))
            .map { it.trim() }
            .filter { it.length >= STITCH_PARA_MIN_CHARS }
        if (paragraphs.size < STITCH_PARA_MIN_COUNT) return false
        var isolated = 0
        for (i in paragraphs.indices) {
            val others = buildString {
                for (j in paragraphs.indices) {
                    if (j != i) append(paragraphs[j])
                }
            }
            if (digramJaccard(paragraphs[i], others) < STITCH_PARA_SIM_MAX) {
                isolated++
            }
        }
        return isolated >= 2 && isolated.toDouble() / paragraphs.size >= STITCH_CUT_RATIO
    }

    /**
     * Among probed bodies for the same chapter, demote origins that disagree with a
     * *trustworthy* agreeing cluster.
     *
     * Pure majority peer-similarity is unsafe: many pirate mirrors share the same
     * anti-theft / injected fanfic, so the rare real chapter becomes the “outlier”.
     * Authority must be majority non-stitched; with a local reference it must also
     * resemble that chapter. Without a trustworthy authority, return empty (no demotion).
     * Without reference, only demote stitched outliers — never demote a coherent minority
     * solely because the majority agrees or is longer (padded spam is often longer).
     */
    fun multiSourceOutlierOrigins(
        samples: Map<String, String>,
        referenceContent: String? = null,
        minSamples: Int = MULTI_SOURCE_MIN_SAMPLES,
    ): Set<String> {
        if (samples.size < minSamples) return emptySet()
        val origins = samples.keys.toList()
        val clusters = connectedClusters(origins) { a, b ->
            digramJaccard(samples.getValue(a), samples.getValue(b)) >= MULTI_SOURCE_CLUSTER_MIN_SIM
        }.filter { it.size >= 2 }
        if (clusters.isEmpty()) return emptySet()

        val ref = referenceContent?.takeIf { it.length >= REFERENCE_MIN_CHARS }
        val scored = clusters.mapNotNull { members ->
            val texts = members.map { samples.getValue(it) }
            val stitchRatio = texts.count { looksLikeStitchedParagraphs(it) }.toDouble() / texts.size
            if (stitchRatio >= STITCH_CUT_RATIO) return@mapNotNull null
            val avgRef = ref?.let { r -> texts.map { digramJaccard(it, r) }.average() }
            if (ref != null && (avgRef ?: 0.0) < MULTI_SOURCE_AUTH_REF_MIN) return@mapNotNull null
            val medianLen = texts.map { it.length }.sorted()[texts.size / 2]
            // With reference, similarity dominates size so padded majority spam cannot win.
            val score = if (ref != null) {
                (avgRef ?: 0.0) * 40.0 + members.size +
                    kotlin.math.ln(1.0 + medianLen) / 4.0 - stitchRatio * 8.0
            } else {
                members.size * 2.0 - stitchRatio * 8.0 +
                    kotlin.math.ln(1.0 + medianLen) / 4.0
            }
            members to score
        }
        val authority = scored.maxByOrNull { it.second } ?: return emptySet()
        val authMembers = authority.first
        val authSet = authMembers.toSet()

        val outliers = LinkedHashSet<String>()
        for (origin in origins) {
            if (origin in authSet) continue
            val text = samples.getValue(origin)
            val maxSimToAuth = authMembers.maxOf { digramJaccard(text, samples.getValue(it)) }
            if (maxSimToAuth >= MULTI_SOURCE_OUTLIER_MAX_SIM) continue
            val stitched = looksLikeStitchedParagraphs(text)
            if (ref != null) {
                val refSim = digramJaccard(text, ref)
                // Like the local chapter ⇒ keep, even if shorter than padded spam authority.
                if (refSim >= MULTI_SOURCE_AUTH_REF_MIN) continue
                val unlikeRef = refSim < REFERENCE_SIM_HIJACK_MAX
                if (unlikeRef || stitched) {
                    outliers.add(origin)
                }
            } else if (stitched) {
                outliers.add(origin)
            }
        }
        return outliers
    }

    /** Union-find clusters over [nodes] where [sameCluster] is true for an edge. */
    internal fun connectedClusters(
        nodes: List<String>,
        sameCluster: (String, String) -> Boolean,
    ): List<List<String>> {
        val parent = IntArray(nodes.size) { it }
        fun find(i: Int): Int {
            var x = i
            while (parent[x] != x) {
                parent[x] = parent[parent[x]]
                x = parent[x]
            }
            return x
        }
        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[ra] = rb
        }
        for (i in nodes.indices) {
            for (j in i + 1 until nodes.size) {
                if (sameCluster(nodes[i], nodes[j])) union(i, j)
            }
        }
        return nodes.indices.groupBy { find(it) }.values.map { idxs -> idxs.map { nodes[it] } }
    }

    /** Character digram Jaccard; Android-free structural similarity. */
    fun digramJaccard(a: String, b: String): Double {
        val da = digrams(a)
        val db = digrams(b)
        if (da.isEmpty() || db.isEmpty()) return 0.0
        var inter = 0
        for (d in da) {
            if (d in db) inter++
        }
        val union = da.size + db.size - inter
        if (union <= 0) return 0.0
        return inter.toDouble() / union
    }

    private fun digrams(text: String): Set<String> {
        // Drop ultra-common function chars so cross-novel paragraphs don't "match" on 的/了/是.
        val normalized = buildString(text.length) {
            for (ch in text) {
                if (ch.isWhitespace()) continue
                if (ch in frequentFunctionChars) continue
                append(ch)
            }
        }
        if (normalized.length < 2) return emptySet()
        val out = HashSet<String>(normalized.length)
        for (i in 0 until normalized.length - 1) {
            out.add(normalized.substring(i, i + 2))
        }
        return out
    }

    private val frequentFunctionChars =
        ("的了是在不人有我他这个们中来上大为和地到以说时要就出会可也你对生能而"
                + "那得于着下自之年过发后作里用道行所然方后义话合回当与想看关点经").toSet()

    fun chapterKey(chapterIndex: Int, chapterTitle: String?): String {
        val pure = pureChapterName(chapterTitle)
        val num = chapterNum(chapterTitle)
        return "$num|$pure|${chapterIndex.coerceAtLeast(0)}"
    }

    fun alignIndex(chapterIndex: Int, chapterTitle: String, toc: List<BookChapter>): Int? {
        return alignResult(chapterIndex, chapterTitle, toc)?.index
    }

    /**
     * Align with a quality score: exact pure=1.0, chapter-num=0.7, containment=0.4.
     */
    fun alignResult(
        chapterIndex: Int,
        chapterTitle: String,
        toc: List<BookChapter>,
    ): AlignResult? {
        if (toc.isEmpty()) return null
        if (chapterTitle.isBlank()) {
            return chapterIndex.takeIf { it in toc.indices }?.let { AlignResult(it, 0.5) }
        }
        val expected = parseProbeKey(chapterKey(chapterIndex, chapterTitle))
        val windowMin = max(0, chapterIndex - 10)
        val windowMax = min(toc.lastIndex, max(chapterIndex, 0) + 10)
        var numMatch: AlignResult? = null
        for (i in windowMin..windowMax) {
            val actual = parseProbeKey(chapterKey(i, toc[i].title))
            if (expected.pure.isNotEmpty() && expected.pure == actual.pure) {
                return AlignResult(i, 1.0)
            }
            if (expected.num > 0 && expected.num == actual.num) {
                numMatch = AlignResult(i, 0.7)
            }
        }
        if (numMatch != null) return numMatch
        for (i in toc.indices) {
            if (i in windowMin..windowMax) continue
            val actual = parseProbeKey(chapterKey(i, toc[i].title))
            if (expected.pure.isNotEmpty() && expected.pure == actual.pure) {
                return AlignResult(i, 0.95)
            }
            if (expected.num > 0 && expected.num == actual.num) {
                return AlignResult(i, 0.65)
            }
        }
        if (expected.pure.length >= 4) {
            for (i in windowMin..windowMax) {
                val actual = parseProbeKey(chapterKey(i, toc[i].title))
                if (actual.pure.length >= 4 &&
                    (expected.pure.contains(actual.pure) || actual.pure.contains(expected.pure))
                ) {
                    return AlignResult(i, 0.4)
                }
            }
        }
        return null
    }

    fun rankStatus(status: String?): Int = when (status) {
        ChangeSourceChapterProbe.STATUS_OK -> 0
        ChangeSourceChapterProbe.STATUS_TOC_OK -> 1
        null, "" -> 2
        ChangeSourceChapterProbe.STATUS_NO_CHAPTER -> 3
        ChangeSourceChapterProbe.STATUS_CONTENT_FAIL -> 4
        else -> 2
    }

    fun sortSearchBooks(
        books: List<SearchBook>,
        probeByOrigin: Map<String, ChangeSourceChapterProbe>,
        bookScore: (SearchBook) -> Int,
        sourceScore: (String) -> Int,
    ): List<SearchBook> {
        return books.sortedWith(
            compareBy<SearchBook> { rankStatus(probeByOrigin[it.origin]?.status) }
                .thenByDescending { probeByOrigin[it.origin]?.score ?: 0.0 }
                .thenByDescending { bookScore(it) }
                .thenByDescending { sourceScore(it.origin) }
                .thenBy { it.originOrder }
        )
    }

    fun needsTocAlign(status: String?): Boolean = when (status) {
        ChangeSourceChapterProbe.STATUS_OK,
        ChangeSourceChapterProbe.STATUS_NO_CHAPTER,
        ChangeSourceChapterProbe.STATUS_TOC_OK -> false
        else -> true
    }

    /**
     * Priority queue for TOC work: liked / fast / early ask-order first.
     * Caps to [TOC_ALIGN_CAP]. Already-aligned origins are excluded.
     */
    fun prioritizeForTocAlign(
        books: List<SearchBook>,
        probeByOrigin: Map<String, ChangeSourceChapterProbe>,
        bookScore: (SearchBook) -> Int,
        sourceScore: (String) -> Int,
        cap: Int = TOC_ALIGN_CAP,
    ): List<SearchBook> {
        return books.asSequence()
            .filter { needsTocAlign(probeByOrigin[it.origin]?.status) }
            .sortedWith(
                compareByDescending<SearchBook> { bookScore(it) }
                    .thenByDescending { sourceScore(it.origin) }
                    .thenBy {
                        val rt = it.respondTime
                        if (rt < 0) Int.MAX_VALUE else rt
                    }
                    .thenBy { it.originOrder }
            )
            .take(cap)
            .toList()
    }

    fun countUsableAlignments(
        probeByOrigin: Map<String, ChangeSourceChapterProbe>,
        origins: Set<String>? = null,
    ): Int {
        return probeByOrigin.values.count {
            (origins == null || it.origin in origins) &&
                    (it.status == ChangeSourceChapterProbe.STATUS_OK ||
                            it.status == ChangeSourceChapterProbe.STATUS_TOC_OK)
        }
    }

    fun countOk(
        probeByOrigin: Map<String, ChangeSourceChapterProbe>,
        origins: Set<String>? = null,
    ): Int {
        return probeByOrigin.values.count {
            (origins == null || it.origin in origins) &&
                    it.status == ChangeSourceChapterProbe.STATUS_OK
        }
    }

    fun shouldStopTocAlign(
        usableAlignments: Int,
        target: Int = TOC_OK_TARGET,
    ): Boolean = usableAlignments >= target

    fun shouldStopContentProbe(
        okCount: Int,
        target: Int = CONTENT_OK_EARLY_STOP,
    ): Boolean = okCount >= target

    fun pickContentProbeOrigins(
        books: List<SearchBook>,
        probeByOrigin: Map<String, ChangeSourceChapterProbe>,
        topK: Int = TOP_K_CONTENT,
    ): List<SearchBook> {
        return books.asSequence()
            .filter { book ->
                when (probeByOrigin[book.origin]?.status) {
                    ChangeSourceChapterProbe.STATUS_OK,
                    ChangeSourceChapterProbe.STATUS_NO_CHAPTER,
                    ChangeSourceChapterProbe.STATUS_CONTENT_FAIL -> false
                    else -> true
                }
            }
            .take(topK)
            .toList()
    }

    data class ProbeKeyParts(val num: Int, val pure: String, val index: Int)

    fun parseProbeKey(key: String): ProbeKeyParts {
        val parts = key.split("|", limit = 3)
        return ProbeKeyParts(
            num = parts.getOrNull(0)?.toIntOrNull() ?: -1,
            pure = parts.getOrNull(1).orEmpty(),
            index = parts.getOrNull(2)?.toIntOrNull() ?: 0,
        )
    }

    private val whitespace = "\\s".toRegex()
    private val pureStrip =
        "[^\\w\\u4E00-\\u9FEF〇\\u3400-\\u4DBF\\u20000-\\u2A6DF\\u2A700-\\u2EBEF]".toRegex()
    private val chapterNumPattern1 =
        Regex(".*?第([\\d零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+)[章节篇回集话]")
    private val chapterNumPattern2 =
        Regex("^(?:[\\d零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+[,:、])*([\\d零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+)(?:[,:、]|\\.[^\\d])")
    private val chineseDigits = mapOf(
        '零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
        '十' to 10, '百' to 100, '千' to 1000, '万' to 10000,
        '壹' to 1, '贰' to 2, '叁' to 3, '肆' to 4, '伍' to 5,
        '陆' to 6, '柒' to 7, '捌' to 8, '玖' to 9, '拾' to 10,
        '佰' to 100, '仟' to 1000,
    )

    private fun pureChapterName(chapterName: String?): String {
        if (chapterName.isNullOrEmpty()) return ""
        return fullToHalf(chapterName)
            .replace(whitespace, "")
            .replace(pureStrip, "")
    }

    private fun chapterNum(chapterName: String?): Int {
        if (chapterName.isNullOrEmpty()) return -1
        val half = fullToHalf(chapterName).replace(whitespace, "")
        val raw = chapterNumPattern1.find(half)?.groupValues?.get(1)
            ?: chapterNumPattern2.find(half)?.groupValues?.get(1)
            ?: return -1
        return chineseOrArabicToInt(raw)
    }

    private fun fullToHalf(input: String): String {
        val c = input.toCharArray()
        for (i in c.indices) {
            when (c[i].code) {
                12288 -> c[i] = ' '
                in 65281..65374 -> c[i] = (c[i].code - 65248).toChar()
            }
        }
        return String(c)
    }

    private fun chineseOrArabicToInt(raw: String): Int {
        raw.toIntOrNull()?.let { return it }
        if (raw.isEmpty()) return -1
        var result = 0
        var tmp = 0
        for (ch in raw) {
            val v = chineseDigits[ch] ?: return -1
            when {
                v >= 10 -> {
                    if (tmp == 0) tmp = 1
                    result += tmp * v
                    tmp = 0
                }
                else -> tmp = tmp * 10 + v
            }
        }
        return result + tmp
    }
}
