package io.legado.app.model.checkalgo

import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.ChangeSourceChapterProbe
import io.legado.app.data.entities.SearchBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangeChapterVerifyTest {

    @Test
    fun chapterKeyStableForSameTitle() {
        val a = ChangeChapterVerify.chapterKey(12, "第十二章 试炼")
        val b = ChangeChapterVerify.chapterKey(12, "第十二章 试炼")
        assertEquals(a, b)
        val parts = ChangeChapterVerify.parseProbeKey(a)
        assertTrue(parts.pure.isNotEmpty())
        assertEquals(12, parts.index)
    }

    @Test
    fun alignIndexMatchesSameTitle() {
        val toc = listOf(
            chapter(0, "第一章 开始"),
            chapter(1, "第二章 成长"),
            chapter(2, "第三章 结局"),
        )
        val idx = ChangeChapterVerify.alignIndex(1, "第二章 成长", toc)
        assertEquals(1, idx)
    }

    @Test
    fun alignIndexNullWhenMissing() {
        val toc = listOf(
            chapter(0, "序章"),
            chapter(1, "番外"),
        )
        assertNull(ChangeChapterVerify.alignIndex(50, "第五十章 不存在的章节标题XYZ", toc))
    }

    @Test
    fun rankPutsOkBeforeNoChapter() {
        assertTrue(
            ChangeChapterVerify.rankStatus(ChangeSourceChapterProbe.STATUS_OK)
                    < ChangeChapterVerify.rankStatus(ChangeSourceChapterProbe.STATUS_NO_CHAPTER)
        )
        assertTrue(
            ChangeChapterVerify.rankStatus(ChangeSourceChapterProbe.STATUS_TOC_OK)
                    < ChangeChapterVerify.rankStatus(null)
        )
    }

    @Test
    fun sortSearchBooksOrdersByProbeStatus() {
        val books = listOf(
            search("a", 3),
            search("b", 1),
            search("c", 2),
        )
        val probes = mapOf(
            "a" to probe("a", ChangeSourceChapterProbe.STATUS_NO_CHAPTER),
            "b" to probe("b", ChangeSourceChapterProbe.STATUS_OK, 900.0),
            "c" to probe("c", ChangeSourceChapterProbe.STATUS_TOC_OK),
        )
        val sorted = ChangeChapterVerify.sortSearchBooks(
            books = books,
            probeByOrigin = probes,
            bookScore = { 0 },
            sourceScore = { 0 },
        )
        assertEquals(listOf("b", "c", "a"), sorted.map { it.origin })
    }

    @Test
    fun pickContentProbeSkipsOkAndHardFails() {
        val books = listOf(
            search("ok", 0),
            search("toc", 1),
            search("unk", 2),
            search("miss", 3),
            search("fail", 4),
        )
        val probes = mapOf(
            "ok" to probe("ok", ChangeSourceChapterProbe.STATUS_OK, 10.0),
            "toc" to probe("toc", ChangeSourceChapterProbe.STATUS_TOC_OK),
            "miss" to probe("miss", ChangeSourceChapterProbe.STATUS_NO_CHAPTER),
            "fail" to probe("fail", ChangeSourceChapterProbe.STATUS_CONTENT_FAIL),
        )
        val picked = ChangeChapterVerify.pickContentProbeOrigins(books, probes, topK = 8)
        assertEquals(listOf("toc", "unk"), picked.map { it.origin })
    }

    @Test
    fun topKConstantIsEight() {
        assertEquals(8, ChangeChapterVerify.TOP_K_CONTENT)
    }

    @Test
    fun shortContainmentDoesNotMisalign() {
        val toc = listOf(
            chapter(0, "第一章开始"),
            chapter(1, "第二章成长"),
        )
        // Short fragment must not match via contains()
        assertNull(ChangeChapterVerify.alignIndex(5, "章开", toc))
    }

    @Test
    fun chineseChapterNumAlignsInWindow() {
        val toc = listOf(
            chapter(0, "第十章 序"),
            chapter(1, "第十一章 战"),
            chapter(2, "第十二章 试炼"),
        )
        assertEquals(2, ChangeChapterVerify.alignIndex(2, "第十二章 试炼", toc))
    }

    @Test
    fun alignResultExactQualityIsOne() {
        val toc = listOf(chapter(0, "第一章 开始"), chapter(1, "第二章 成长"))
        val r = ChangeChapterVerify.alignResult(1, "第二章 成长", toc)
        assertEquals(1, r?.index)
        assertEquals(1.0, r?.quality ?: -1.0, 0.001)
    }

    @Test
    fun prioritizeForTocAlignCapsAndOrders() {
        val books = (0 until 30).map { i ->
            search("o$i", order = i).apply {
                respondTime = 1000 - i // higher i = faster
            }
        }
        val picked = ChangeChapterVerify.prioritizeForTocAlign(
            books = books,
            probeByOrigin = emptyMap(),
            bookScore = { 0 },
            sourceScore = { 0 },
            cap = ChangeChapterVerify.TOC_ALIGN_CAP,
        )
        assertEquals(ChangeChapterVerify.TOC_ALIGN_CAP, picked.size)
        // Faster respondTime first when scores equal
        assertEquals("o29", picked.first().origin)
        assertTrue(picked.none { it.origin == "o0" })
    }

    @Test
    fun prioritizeSkipsAlreadyAligned() {
        val books = listOf(search("a", 0), search("b", 1), search("c", 2))
        val probes = mapOf(
            "a" to probe("a", ChangeSourceChapterProbe.STATUS_TOC_OK, 1.0),
            "b" to probe("b", ChangeSourceChapterProbe.STATUS_OK, 100.0),
        )
        val picked = ChangeChapterVerify.prioritizeForTocAlign(
            books = books,
            probeByOrigin = probes,
            bookScore = { 0 },
            sourceScore = { 0 },
        )
        assertEquals(listOf("c"), picked.map { it.origin })
    }

    @Test
    fun tocAndContentEarlyStopPredicates() {
        assertTrue(ChangeChapterVerify.shouldStopTocAlign(12))
        assertTrue(!ChangeChapterVerify.shouldStopTocAlign(11))
        assertTrue(ChangeChapterVerify.shouldStopContentProbe(ChangeChapterVerify.TOP_K_CONTENT))
        assertTrue(!ChangeChapterVerify.shouldStopContentProbe(ChangeChapterVerify.TOP_K_CONTENT - 1))
        assertEquals(ChangeChapterVerify.TOP_K_CONTENT, ChangeChapterVerify.CONTENT_OK_EARLY_STOP)
    }

    @Test
    fun evaluateContentRejectsShortAndAntiTheft() {
        assertTrue(
            ChangeChapterVerify.evaluateContent("太短") is ChangeChapterVerify.ContentQuality.TooShort
        )
        val shell = "您现在看的是防盗章节，正确章节请访问正版。" + "x".repeat(200)
        assertTrue(
            ChangeChapterVerify.evaluateContent(shell) is ChangeChapterVerify.ContentQuality.AntiTheft
        )
        val ok = "这是正文。" + "内容".repeat(80)
        assertTrue(
            ChangeChapterVerify.evaluateContent(ok) is ChangeChapterVerify.ContentQuality.Ok
        )
    }

    @Test
    fun evaluateContentDetectsStitchedMultiNovelParagraphs() {
        val stitched = listOf(
            "罗峰站在黑洞边缘，感受宇宙深处传来的威压，手中战刀微微震动不止。",
            "萧炎看向药老虚影，纳戒里的异火忽然躁动起来，焚决开始疯狂运转。",
            "叶凡走出北斗仙棺，古禁地的气息让周围修士纷纷退避三舍不敢靠近。",
            "韩立掐诀祭出青竹蜂云剑，对面的魔修脸色瞬间变得铁青无比。",
        ).joinToString("\n\n")
        assertTrue(stitched.length >= ChangeChapterVerify.MIN_CONTENT_CHARS)
        assertTrue(ChangeChapterVerify.looksLikeStitchedParagraphs(stitched))
        assertTrue(
            ChangeChapterVerify.evaluateContent(stitched) is ChangeChapterVerify.ContentQuality.Hijack
        )

        val coherent = listOf(
            "罗峰站在黑洞边缘，感受宇宙深处传来的威压，宇宙之力在经脉中游荡不止一刻。",
            "他握紧战刀，宇宙之力顺着经脉缓缓流转，黑洞的威压愈发强烈难当，几乎令人窒息。",
            "不远处的飞船里，同伴正等待罗峰发出下一步指令，关注着黑洞边缘的情况变化与能量波动异常。",
        ).joinToString("\n\n")
        assertTrue(
            "len=${coherent.length}",
            coherent.length >= ChangeChapterVerify.MIN_CONTENT_CHARS,
        )
        assertTrue(!ChangeChapterVerify.looksLikeStitchedParagraphs(coherent))
        assertTrue(
            ChangeChapterVerify.evaluateContent(coherent)
                    is ChangeChapterVerify.ContentQuality.Ok
        )
    }

    @Test
    fun multiSourceConsensusFlagsOutlierBodies() {
        val goodA = "罗峰站在黑洞边缘，感受宇宙之力缓缓汇入体内。" + "修炼".repeat(40)
        val goodB = "罗峰站在黑洞边缘，感受宇宙之力不断汇入体内。" + "修炼".repeat(38)
        val goodC = "罗峰立于黑洞之侧，宇宙之力自四面八方汇来。" + "修炼".repeat(36)
        val bad = "萧炎看向药老，异火在体内疯狂咆哮。" + "焚决".repeat(40)
        val reference = "罗峰站在黑洞边缘，感受宇宙之力。" + "修炼".repeat(120)
        val outliers = ChangeChapterVerify.multiSourceOutlierOrigins(
            samples = mapOf(
                "a" to goodA,
                "b" to goodB,
                "c" to goodC,
                "x" to bad,
            ),
            referenceContent = reference,
        )
        assertEquals(setOf("x"), outliers)
    }

    @Test
    fun multiSourceConsensusDoesNotDemoteMinorityWhenMajorityIsCoherentSpam() {
        // Same injected fanfic on many mirrors; only one real chapter body.
        val spam = "恭喜宿主收徒气运之子，奖励帝品传承，万倍返还已到账。" + "系统".repeat(50)
        val spamB = "恭喜宿主收徒气运之子，奖励帝品传承，万倍返还已经到账。" + "系统".repeat(48)
        val spamC = "恭喜宿主收徒气运之子并奖励帝品传承，万倍返还已到账。" + "系统".repeat(46)
        val good = "罗峰站在黑洞边缘，感受宇宙之力缓缓汇入体内。" + "修炼".repeat(50)
        // No local reference: must not treat the real chapter as the outlier.
        assertTrue(
            ChangeChapterVerify.multiSourceOutlierOrigins(
                mapOf("s1" to spam, "s2" to spamB, "s3" to spamC, "g" to good)
            ).isEmpty()
        )
        // Padded spam longer than a shorter real chapter — still must not demote without ref.
        val shortGood = "罗峰站在黑洞边缘，感受宇宙之力缓缓汇入。" + "修炼".repeat(55)
        val paddedSpam = spam + "水".repeat(200)
        assertTrue(
            "shortGood=${shortGood.length}",
            shortGood.length >= ChangeChapterVerify.MIN_CONTENT_CHARS,
        )
        assertTrue(shortGood.length < paddedSpam.length)
        assertTrue(
            ChangeChapterVerify.multiSourceOutlierOrigins(
                mapOf(
                    "s1" to paddedSpam,
                    "s2" to spamB + "水".repeat(200),
                    "s3" to spamC + "水".repeat(200),
                    "g" to shortGood,
                )
            ).isEmpty()
        )
        // With reference: need ≥2 real bodies to form a trustworthy authority cluster.
        val good2 = "罗峰站在黑洞边缘，感受宇宙之力不断汇入体内。" + "修炼".repeat(48)
        val reference = "罗峰站在黑洞边缘，感受宇宙之力。" + "修炼".repeat(120)
        val withRef = ChangeChapterVerify.multiSourceOutlierOrigins(
            samples = mapOf(
                "s1" to spam,
                "s2" to spamB,
                "s3" to spamC,
                "g" to good,
                "g2" to good2,
            ),
            referenceContent = reference,
        )
        assertEquals(setOf("s1", "s2", "s3"), withRef)
        // Single real body + spam below AUTH_REF_MIN ⇒ no trustworthy authority ⇒ empty.
        assertTrue(
            ChangeChapterVerify.multiSourceOutlierOrigins(
                samples = mapOf("s1" to spam, "s2" to spamB, "s3" to spamC, "g" to good),
                referenceContent = reference,
            ).isEmpty()
        )
        // Like reference but shorter than padded authority — keep.
        val shortLikeRef = "罗峰站在黑洞边缘，感受宇宙之力。" + "修炼".repeat(40)
        assertTrue(
            !ChangeChapterVerify.multiSourceOutlierOrigins(
                samples = mapOf(
                    "a" to good,
                    "b" to good2,
                    "c" to good + "忽然。",
                    "short" to shortLikeRef,
                ),
                referenceContent = reference,
            ).contains("short")
        )
    }

    @Test
    fun multiSourceConsensusDemotesStitchedOutlierWithoutReference() {
        val goodA = "罗峰站在黑洞边缘，感受宇宙之力缓缓汇入体内。" + "修炼".repeat(40)
        val goodB = "罗峰站在黑洞边缘，感受宇宙之力不断汇入体内。" + "修炼".repeat(38)
        val goodC = "罗峰立于黑洞之侧，宇宙之力自四面八方汇来。" + "修炼".repeat(36)
        val stitched = listOf(
            "萧炎看向药老，异火在体内疯狂咆哮，焚决运转不止一刻。",
            "叶凡盘坐虚空，圣体符文亮起，帝兵虚影笼罩周身四周。",
            "韩立掐诀吐纳，周围灵气潮水般涌入，丹田灵力暴涨难抑。",
        ).joinToString("\n\n")
        assertTrue(ChangeChapterVerify.looksLikeStitchedParagraphs(stitched))
        val outliers = ChangeChapterVerify.multiSourceOutlierOrigins(
            mapOf("a" to goodA, "b" to goodB, "c" to goodC, "x" to stitched)
        )
        assertEquals(setOf("x"), outliers)
    }

    @Test
    fun evaluateContentDetectsHijackViaLowReferenceSimilarity() {
        val reference = "罗峰站在黑洞边缘，感受宇宙之力。" + "修炼".repeat(120)
        val hijack = """
            吞噬星空：收徒万倍返还
            恭喜宿主收徒气运之子，奖励帝品传承！
            万倍返还已到账，请继续观看广告小说……
        """.trimIndent() + "水".repeat(120)
        val q = ChangeChapterVerify.evaluateContent(
            hijack,
            ChangeChapterVerify.ContentEvalContext(
                expectedChars = reference.length,
                referenceContent = reference,
            ),
        )
        assertTrue(q is ChangeChapterVerify.ContentQuality.Hijack)

        val sameChapterVariant = "罗峰站在黑洞边缘，感受宇宙之力。" + "修炼".repeat(110) + "忽然。"
        val ok = ChangeChapterVerify.evaluateContent(
            sameChapterVariant,
            ChangeChapterVerify.ContentEvalContext(
                expectedChars = reference.length,
                referenceContent = reference,
            ),
        )
        assertTrue(ok is ChangeChapterVerify.ContentQuality.Ok)
        assertTrue(
            ChangeChapterVerify.digramJaccard(sameChapterVariant, reference) >=
                    ChangeChapterVerify.REFERENCE_SIM_HIJACK_MAX
        )
    }

    @Test
    fun evaluateContentRelativeLength() {
        val text = "开篇。" + "字".repeat(150) // ~153 chars
        val tooShortVsExpected = ChangeChapterVerify.evaluateContent(
            text,
            ChangeChapterVerify.ContentEvalContext(expectedChars = 2000),
        )
        assertTrue(tooShortVsExpected is ChangeChapterVerify.ContentQuality.TooShort)
        assertTrue(
            ChangeChapterVerify.evaluateContent(text) is ChangeChapterVerify.ContentQuality.Ok
        )
    }

    @Test
    fun countOkScopedToOrigins() {
        val probes = mapOf(
            "a" to probe("a", ChangeSourceChapterProbe.STATUS_OK, 10.0),
            "b" to probe("b", ChangeSourceChapterProbe.STATUS_OK, 20.0),
            "c" to probe("c", ChangeSourceChapterProbe.STATUS_TOC_OK, 1.0),
        )
        assertEquals(2, ChangeChapterVerify.countOk(probes))
        assertEquals(1, ChangeChapterVerify.countOk(probes, setOf("a", "c")))
        assertEquals(2, ChangeChapterVerify.countUsableAlignments(probes, setOf("a", "c")))
    }

    @Test
    fun sortPrefersHigherTocQuality() {
        val books = listOf(search("low", 0), search("high", 1))
        val probes = mapOf(
            "low" to probe("low", ChangeSourceChapterProbe.STATUS_TOC_OK, 0.4),
            "high" to probe("high", ChangeSourceChapterProbe.STATUS_TOC_OK, 1.0),
        )
        val sorted = ChangeChapterVerify.sortSearchBooks(
            books = books,
            probeByOrigin = probes,
            bookScore = { 0 },
            sourceScore = { 0 },
        )
        assertEquals(listOf("high", "low"), sorted.map { it.origin })
    }

    private fun chapter(index: Int, title: String) = BookChapter().apply {
        this.index = index
        this.title = title
        this.url = "https://example.com/$index"
    }

    private fun search(origin: String, order: Int) = SearchBook(
        bookUrl = "https://example.com/book/$origin",
        origin = origin,
        originName = origin,
        name = "书",
        author = "作者",
        originOrder = order,
    )

    private fun probe(origin: String, status: String, score: Double = 0.0) =
        ChangeSourceChapterProbe(
            name = "书",
            author = "作者",
            origin = origin,
            chapterKey = "k",
            status = status,
            score = score,
        )
}
