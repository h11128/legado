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
    /**
     * Phrase/regex anti-theft still applies under this length.
     * Longer pages with hijack titles are still caught by [looksLikeWrongBookHijack].
     */
    const val ANTI_THEFT_PHRASE_MAX_CHARS = 1200
    /** Relative length: fail when below this fraction of expected chapter size. */
    const val RELATIVE_MIN_RATIO = 0.22
    /** Only apply relative check when expected size is at least this. */
    const val RELATIVE_MIN_EXPECTED = 400

    data class ContentEvalContext(
        val bookName: String = "",
        /** Typical chapter length from current book / peer OK probes; null = skip relative. */
        val expectedChars: Int? = null,
    )

    /**
     * Common pirate-site shells, paywall blurbs, and Qidian-style delayed "防盗章".
     * Prefer distinctive multi-char phrases over single words like "VIP".
     */
    private val antiTheftMarkers = listOf(
        "防盗章节", "防盗章", "您现在看的是防盗", "本章为防盗", "内容更新延迟",
        "订阅比例", "72小时后", "24小时后", "四十八小时", "七十二小时",
        "请支持正版", "请购买正版", "请到正版", "正版平台", "起点中文网",
        "本章未完结", "本章未完", "内容加载失败", "章节内容加载失败",
        "请购买本章", "订阅后阅读", "付费章节", "开通VIP", "开通vip", "VIP章节",
        "完整版请", "下载客户端", "下载APP", "下载app", "正在手打",
        "章节内容缺失", "抱歉，本章", "加入书架即可", "加入书架后",
        "关闭浏览器的阅读模式", "关闭广告屏蔽", "只支持手机浏览器",
        "本站所有小说都是转载", "转载至本站只是为了宣传",
    )

    /** Site chrome / remember-domain spam (笔趣阁系). */
    private val siteChromeMarkers = listOf(
        "一秒记住", "天才一秒记住", "请记住本站", "请牢记本站", "请牢记收藏",
        "手机版阅读网址", "手机同步阅读", "最快更新", "无弹窗", "无错小说",
        "纯文字在线", "破防盗完美章节", "搜索引擎各种小说",
        "请移步到", "清爽无广告", "相关阅读：", "猜你喜欢：",
    )

    /**
     * Regex shells that survive after replace rules strip domains.
     * Keep Android-free: Kotlin Regex only.
     */
    private val antiTheftRegexes = listOf(
        Regex("一秒记住.{0,12}【?.{0,12}】?"),
        Regex("天才一秒记住"),
        Regex("请记住.{0,8}(本站|域名|网址)"),
        Regex("您现在看的是防盗"),
        Regex("正确章节请(访问|前往)"),
        Regex("无防盗.{0,6}(免费|阅读|全文)"),
        Regex("最快更新.{0,10}无弹窗"),
        Regex("关闭.{0,6}(阅读模式|畅读模式|小说模式)"),
        Regex("(订阅|购买).{0,8}(比例|章节).{0,12}(防盗|可见)"),
    )

    /**
     * Popular titles / spam hooks frequently injected into unrelated chapters
     * (e.g. 「吞噬星空」「收徒万倍返还」广告劫持). Skipped when [ContentEvalContext.bookName]
     * already contains the same title.
     */
    private val hijackTitleBaits = listOf(
        "吞噬星空", "收徒万倍返还", "万倍返还", "斗破苍穹", "完美世界", "遮天",
        "凡人修仙传", "仙逆", "莽荒纪", "武动乾坤", "大主宰", "斗罗大陆",
        "元尊", "圣墟", "深空彼岸", "剑来", "诡秘之主", "我有一座恐怖屋",
        "全职法师", "逆天邪神", "万古神帝", "帝霸", "一念永恒", "大道朝天",
        "恭喜宿主", "系统已激活", "万界圣师系统", "从斗破开始",
    )

    data class AlignResult(val index: Int, val quality: Double)

    sealed class ContentQuality {
        data class Ok(val length: Int) : ContentQuality()
        data object TooShort : ContentQuality()
        data object AntiTheft : ContentQuality()
        /** Wrong-book / promo injection (e.g. 吞噬星空 + 收徒万倍返还 spam). */
        data object Hijack : ContentQuality()
    }

    /**
     * Decide whether fetched chapter text counts as a real readable chapter.
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

        if (looksLikeWrongBookHijack(text, context.bookName)) {
            return ContentQuality.Hijack
        }

        val head = text.take(500)
        val phraseHit = antiTheftMarkers.any { head.contains(it) || text.contains(it) } ||
                siteChromeMarkers.any { head.contains(it) }
        val regexHit = antiTheftRegexes.any { it.containsMatchIn(head) || it.containsMatchIn(text.take(800)) }
        if ((phraseHit || regexHit) && text.length < ANTI_THEFT_PHRASE_MAX_CHARS) {
            return ContentQuality.AntiTheft
        }
        // Dense site-chrome even in longer pages: many markers in the head.
        val chromeHits = siteChromeMarkers.count { head.contains(it) }
        if (chromeHits >= 2 && text.length < 2000) {
            return ContentQuality.AntiTheft
        }
        return ContentQuality.Ok(text.length)
    }

    /**
     * Detect injected promo / wrong-book blocks such as 「吞噬星空：收徒万倍返还」.
     */
    fun looksLikeWrongBookHijack(content: String, bookName: String): Boolean {
        val text = content.trim()
        if (text.isEmpty()) return false
        val name = bookName.trim()
        val head = text.take(800)
        val baits = hijackTitleBaits.filter { bait ->
            !name.contains(bait) && (head.contains(bait) || text.contains(bait))
        }
        if (baits.isEmpty()) return false
        // Strong: classic dual spam "吞噬星空" + "收徒/万倍返还" style pair.
        val hasDevour = baits.any { it.contains("吞噬") || it == "吞噬星空" }
        val hasReturn = baits.any { it.contains("万倍") || it.contains("收徒") }
        if (hasDevour && hasReturn) return true
        // Strong: system-novel hooks in a book that is not about 系统.
        if (baits.any { it == "恭喜宿主" || it == "系统已激活" || it == "万界圣师系统" } &&
            !name.contains("系统")
        ) {
            return true
        }
        // Multiple unrelated famous titles in one chapter → almost always hijack/ads.
        if (baits.size >= 2) return true
        // Single bait title dominating a short/medium page.
        if (baits.size == 1 && text.length < 1500) {
            val bait = baits.first()
            if (head.indexOf(bait) in 0..120) return true
        }
        return false
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
