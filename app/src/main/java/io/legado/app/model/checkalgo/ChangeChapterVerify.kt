package io.legado.app.model.checkalgo

import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.ChangeSourceChapterProbe
import io.legado.app.data.entities.SearchBook
import kotlin.math.max
import kotlin.math.min

/**
 * Pure helpers for chapter-scoped change-source verify / ranking.
 * Kept Android-free so unit tests can run on the JVM.
 */
object ChangeChapterVerify {

    const val TOP_K_CONTENT = 8

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

    fun chapterKey(chapterIndex: Int, chapterTitle: String?): String {
        val pure = pureChapterName(chapterTitle)
        val num = chapterNum(chapterTitle)
        return "$num|$pure|${chapterIndex.coerceAtLeast(0)}"
    }

    /**
     * Align [chapterIndex]/[chapterTitle] against [toc].
     * Returns null when the chapter cannot be confidently found
     * (→ [ChangeSourceChapterProbe.STATUS_NO_CHAPTER]).
     */
    fun alignIndex(chapterIndex: Int, chapterTitle: String, toc: List<BookChapter>): Int? {
        if (toc.isEmpty()) return null
        if (chapterTitle.isBlank()) {
            return chapterIndex.takeIf { it in toc.indices }
        }
        val expected = parseProbeKey(chapterKey(chapterIndex, chapterTitle))
        val windowMin = max(0, chapterIndex - 10)
        val windowMax = min(toc.lastIndex, max(chapterIndex, 0) + 10)
        var numMatch: Int? = null
        for (i in windowMin..windowMax) {
            val actual = parseProbeKey(chapterKey(i, toc[i].title))
            if (expected.pure.isNotEmpty() && expected.pure == actual.pure) return i
            if (expected.num > 0 && expected.num == actual.num) numMatch = i
        }
        if (numMatch != null) return numMatch
        for (i in toc.indices) {
            if (i in windowMin..windowMax) continue
            val actual = parseProbeKey(chapterKey(i, toc[i].title))
            if (expected.pure.isNotEmpty() && expected.pure == actual.pure) return i
            if (expected.num > 0 && expected.num == actual.num) return i
        }
        // Containment only for longer titles to avoid short false positives (e.g. "一"/"二").
        if (expected.pure.length >= 4) {
            for (i in windowMin..windowMax) {
                val actual = parseProbeKey(chapterKey(i, toc[i].title))
                if (actual.pure.length >= 4 &&
                    (expected.pure.contains(actual.pure) || actual.pure.contains(expected.pure))
                ) {
                    return i
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
        // simple 十/百 forms: 十二, 二十, 一百零二
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
