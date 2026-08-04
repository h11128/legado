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
     * Digram Jaccard below this vs the known-good chapter ⇒ wrong/hijacked text.
     * Same chapter from another source usually stays well above; ads/other books fall near 0.
     */
    const val REFERENCE_SIM_HIJACK_MAX = 0.04

    data class ContentEvalContext(
        /** Typical chapter length from peer OK probes / current chapter; null = skip relative. */
        val expectedChars: Int? = null,
        /** Known-good chapter body (e.g. cached content of the chapter being replaced). */
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
        /** Body is long enough but diverges sharply from the known-good chapter. */
        data object Hijack : ContentQuality()
    }

    /**
     * Decide whether fetched chapter text counts as a real readable chapter.
     *
     * Order (structural first):
     * 1) absolute length floor
     * 2) relative length vs expected
     * 3) digram similarity vs reference chapter (when available)
     * 4) tiny high-precision shell markers only
     */
    fun evaluateContent(
        content: String,
        context: ContentEvalContext = ContentEvalContext(),
    ): ContentQuality {
        val text = content.trim()
        if (text.length < MIN_CONTENT_CHARS) return ContentQuality.TooShort

        val expected = context.expectedChars
        if (expected != null && expected >= RELATIVE_MIN_EXPECTED) {
            val floor = max(MIN_CONTENT_CHARS, (expected * RELATIVE_MIN_RATIO).toInt())
            if (text.length < floor) return ContentQuality.TooShort
        }

        val reference = context.referenceContent?.trim().orEmpty()
        if (reference.length >= REFERENCE_MIN_CHARS && text.length >= MIN_CONTENT_CHARS) {
            val sim = digramJaccard(text, reference)
            if (sim < REFERENCE_SIM_HIJACK_MAX) {
                return ContentQuality.Hijack
            }
        }

        if (highPrecisionShellMarkers.any { text.contains(it) } &&
            text.length < RELATIVE_MIN_EXPECTED * 2
        ) {
            return ContentQuality.AntiTheft
        }
        return ContentQuality.Ok(text.length)
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
        val normalized = text.replace("\\s".toRegex(), "")
        if (normalized.length < 2) return emptySet()
        val out = HashSet<String>(normalized.length)
        for (i in 0 until normalized.length - 1) {
            out.add(normalized.substring(i, i + 2))
        }
        return out
    }

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
