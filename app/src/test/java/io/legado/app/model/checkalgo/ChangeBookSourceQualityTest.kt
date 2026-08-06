package io.legado.app.model.checkalgo

import io.legado.app.data.entities.SearchBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangeBookSourceQualityTest {

    @Test
    fun tocConsistentWithinBand() {
        assertTrue(ChangeBookSourceQuality.tocConsistent(800, 780))
        assertTrue(ChangeBookSourceQuality.tocConsistent(800, 400))
        assertFalse(ChangeBookSourceQuality.tocConsistent(800, 50))
        assertFalse(ChangeBookSourceQuality.tocConsistent(100, 800))
        assertTrue(ChangeBookSourceQuality.tocConsistent(0, 50))
    }

    @Test
    fun latestMatchesLocalByPureTitleOrNum() {
        assertTrue(
            ChangeBookSourceQuality.latestMatchesLocal(
                "第875章 离开，黑人抬棺！震撼的宇宙海强者",
                "第875章 离开，黑人抬棺！震撼的宇宙海强者",
            ) == true
        )
        assertFalse(
            ChangeBookSourceQuality.latestMatchesLocal(
                "第875章 离开，黑人抬棺！震撼的宇宙海强者",
                "交易(校园NP，高H，全C)",
            ) == true
        )
        assertEquals(
            false,
            ChangeBookSourceQuality.latestMatchesLocal(
                "第875章 离开，黑人抬棺",
                "交易(校园NP，高H，全C)",
            )
        )
        assertNull(ChangeBookSourceQuality.latestMatchesLocal(null, "第1章"))
    }

    @Test
    fun latestTitleOutliersDemoteUnlikeLocal() {
        val titles = mapOf(
            "a" to "第875章 离开，黑人抬棺！震撼的宇宙海强者",
            "b" to "第875章 离开，黑人抬棺！震撼的宇宙海强者",
            "c" to "第875章 离开，黑人抬棺！震撼的宇宙海强者",
            "bad" to "交易(校园NP，高H，全C)",
        )
        val outliers = ChangeBookSourceQuality.latestTitleOutliers(
            titlesByOrigin = titles,
            localLatest = "第875章 离开，黑人抬棺！震撼的宇宙海强者",
        )
        assertTrue(outliers.contains("bad"))
        assertFalse(outliers.contains("a"))
    }

    @Test
    fun latestSameChapterNumStillNeedsTitleAffinity() {
        assertEquals(
            false,
            ChangeBookSourceQuality.latestMatchesLocal(
                "第460章 这有些犯规了吧！",
                "第460章 交易校园NP高H全错书插入广告",
            )
        )
    }

    @Test
    fun sortTierPutsContentBadBelowOk() {
        val ok = ChangeBookSourceQuality.contentSortTier(3800, "字数：3800")
        val bad = ChangeBookSourceQuality.contentSortTier(-1, "疑似错书/广告劫持")
        val unknown = ChangeBookSourceQuality.contentSortTier(-1, null)
        assertTrue(ok < bad)
        assertTrue(ok < unknown)
        assertEquals(ChangeBookSourceQuality.TIER_UNKNOWN, unknown)
    }

    @Test
    fun pendingSearchHitRanksAboveContentBad() {
        val pending = ChangeBookSourceQuality.contentSortTier(0, "校验中…")
        val bad = ChangeBookSourceQuality.contentSortTier(-1, "疑似错书/广告劫持")
        val ok = ChangeBookSourceQuality.contentSortTier(3800, "字数：3800")
        assertEquals(ChangeBookSourceQuality.TIER_PENDING, pending)
        assertTrue(ok < pending)
        assertTrue(pending < bad)
    }

    @Test
    fun contentOkIgnoresLatestMetaInHardTier() {
        val ok = ChangeBookSourceQuality.sortTier(
            chapterWordCount = 3800,
            wordCountText = "字数：3800",
            metaTiers = ChangeBookSourceQuality.TIER_LATEST_BAD,
        )
        assertEquals(ChangeBookSourceQuality.TIER_OK, ok)
        assertEquals(1, ChangeBookSourceQuality.softMetaPenalty(ChangeBookSourceQuality.TIER_LATEST_BAD))
        assertEquals(
            0,
            ChangeBookSourceQuality.softMetaPenalty(
                ChangeBookSourceQuality.TIER_LATEST_BAD,
                chapterWordCount = 3800,
            ),
        )
        assertEquals(0, ChangeBookSourceQuality.softMetaPenalty(ChangeBookSourceQuality.TIER_OK))
    }

    @Test
    fun suppressLatestBadgeWhenContentOkAndStrongRef() {
        assertFalse(
            ChangeBookSourceQuality.shouldShowSoftMetaBadge(
                chapterWordCount = 3800,
                contentRefSim = 0.90,
            )
        )
        assertFalse(
            ChangeBookSourceQuality.shouldShowTocMismatchBadge(
                chapterWordCount = 3800,
                contentRefSim = 0.90,
            )
        )
        assertFalse(
            ChangeBookSourceQuality.shouldShowLatestMismatchBadge(
                chapterWordCount = 3800,
                contentRefSim = 0.50,
            )
        )
        assertFalse(
            ChangeBookSourceQuality.shouldShowLatestMismatchBadge(
                chapterWordCount = 3800,
                contentRefSim = null,
            )
        )
        assertFalse(
            ChangeBookSourceQuality.shouldShowLatestMismatchBadge(
                chapterWordCount = 0,
                contentRefSim = null,
            )
        )
        assertTrue(
            ChangeBookSourceQuality.shouldShowLatestMismatchBadge(
                chapterWordCount = 3800,
                contentRefSim = 0.10,
            )
        )
        assertTrue(
            ChangeBookSourceQuality.shouldShowLatestMismatchBadge(
                chapterWordCount = -1,
                contentRefSim = 0.90,
            )
        )
    }

    @Test
    fun respondTimeSortKeyPutsUnknownLast() {
        assertTrue(
            ChangeBookSourceQuality.respondTimeSortKey(500)
                    < ChangeBookSourceQuality.respondTimeSortKey(-1)
        )
    }

    @Test
    fun lengthBandPrefersNearExpected() {
        val near = ChangeBookSourceQuality.lengthBandScore(3800, 4000)
        val far = ChangeBookSourceQuality.lengthBandScore(12000, 4000)
        assertTrue(near > far)
    }

    @Test
    fun softFailedDoesNotVetoOkOrWeakContent() {
        val okSoft = ChangeBookSourceQuality.contentSortTier(
            chapterWordCount = 3800,
            wordCountText = "字数：3800",
            softFailed = true,
        )
        val weakSoft = ChangeBookSourceQuality.contentSortTier(
            chapterWordCount = 200,
            softFailed = true,
        )
        val badSoft = ChangeBookSourceQuality.contentSortTier(
            chapterWordCount = -1,
            wordCountText = "获取失败",
            softFailed = true,
        )
        assertEquals(ChangeBookSourceQuality.TIER_OK, okSoft)
        assertEquals(ChangeBookSourceQuality.TIER_WEAK, weakSoft)
        assertEquals(ChangeBookSourceQuality.TIER_SOFT_FAIL, badSoft)
        assertTrue(okSoft < badSoft)
    }

    @Test
    fun earlyStopUsesThreshold() {
        assertFalse(ChangeBookSourceQuality.shouldEarlyStop(19, enabled = true, target = 20))
        assertTrue(ChangeBookSourceQuality.shouldEarlyStop(20, enabled = true, target = 20))
        assertFalse(ChangeBookSourceQuality.shouldEarlyStop(100, enabled = false, target = 20))
    }

    @Test
    fun backendFromBookUrlReadsSourceQuery() {
        assertEquals(
            "69书吧",
            ChangeBookSourceQuality.backendFromBookUrl(
                "https://v1.gyks.cf/detail?book_id=abc&source=69书吧&tab=小说",
            ),
        )
        assertNull(ChangeBookSourceQuality.backendFromBookUrl("https://example.com/book/1"))
    }

    @Test
    fun usableLatestRejectsAggregatorTipThatIsOnlyBackend() {
        val url = "https://v1.gyks.cf/detail?book_id=x&source=百度"
        assertFalse(ChangeBookSourceQuality.hasUsableSearchLatest("百度", url))
        assertFalse(ChangeBookSourceQuality.hasUsableSearchLatest("", url))
        assertFalse(ChangeBookSourceQuality.hasUsableSearchLatest("详细", url))
        assertTrue(
            ChangeBookSourceQuality.hasUsableSearchLatest(
                "69书吧 第382章 一等天才，万倍返还！",
                "https://v1.gyks.cf/detail?book_id=x&source=69书吧",
            )
        )
        assertEquals(
            "第382章 一等天才，万倍返还！",
            ChangeBookSourceQuality.effectiveLatestChapterTitle(
                "69书吧 第382章 一等天才，万倍返还！",
                "https://v1.gyks.cf/detail?book_id=x&source=69书吧",
            ),
        )
    }

    @Test
    fun authorCompatibleRequiresOverlapWhenLocalSet() {
        assertTrue(
            ChangeBookSourceQuality.authorCompatibleForChangeSource("新乙", "", requireAuthor = false)
        )
        assertTrue(
            ChangeBookSourceQuality.authorCompatibleForChangeSource("", "任何人", requireAuthor = true)
        )
        assertFalse(
            ChangeBookSourceQuality.authorCompatibleForChangeSource("新乙", "", requireAuthor = true)
        )
        assertFalse(
            ChangeBookSourceQuality.authorCompatibleForChangeSource("新乙", "铁匠小笑", requireAuthor = true)
        )
        assertTrue(
            ChangeBookSourceQuality.authorCompatibleForChangeSource("新乙", "新乙", requireAuthor = true)
        )
        assertTrue(
            ChangeBookSourceQuality.authorCompatibleForChangeSource("新乙", "作者：新乙", requireAuthor = true)
        )
    }

    @Test
    fun usableLatestKeepsShortRealTips() {
        assertTrue(ChangeBookSourceQuality.hasUsableSearchLatest("序章", null))
        assertTrue(ChangeBookSourceQuality.hasUsableSearchLatest("第1话 开端", null))
        assertFalse(ChangeBookSourceQuality.hasUsableSearchLatest("详细", null))
        assertFalse(ChangeBookSourceQuality.hasUsableSearchLatest("最新章节", null))
    }

    @Test
    fun acceptableHitRejectsHaiciStyleShell() {
        val shell = SearchBook(
            name = "吞噬星空：收徒万倍返还",
            origin = "http://dict.cn",
            originName = "海词精选（优）",
            bookUrl = "http://dict.cn/%E5%90%9E%E5%99%AC",
            author = "",
            latestChapterTitle = "",
            intro = "该词条未找到_海词词典",
        )
        assertFalse(
            ChangeBookSourceQuality.isAcceptableChangeSourceHit(
                shell,
                "新乙",
                requireAuthor = false,
            )
        )
        val introOnly = shell.copy(latestChapterTitle = "第1章 开始", intro = "该词条未找到_海词词典")
        assertFalse(
            ChangeBookSourceQuality.isAcceptableChangeSourceHit(
                introOnly,
                "新乙",
                requireAuthor = false,
            )
        )
    }

    @Test
    fun nonNovelHostAlwaysRejected() {
        assertTrue(ChangeBookSourceQuality.isNonNovelSearchHost("https://image.baidu.com/search/flip?word=x"))
        assertTrue(ChangeBookSourceQuality.isNonNovelSearchHost("https://zhidao.baidu.com/msearch?word=x"))
        assertFalse(ChangeBookSourceQuality.isNonNovelSearchHost("https://novel.html5.qq.com/book/1"))
        val img = SearchBook(
            name = "任意书名",
            origin = "https://image.baidu.com",
            originName = "百度图片",
            bookUrl = "https://image.baidu.com/search/flip?word=任意书名",
            author = "假作者",
            latestChapterTitle = "第1章 开端",
            intro = "这是一段足够长的简介用来骗过空最新章回退门禁，但 host 仍应拦截。",
        )
        assertFalse(
            ChangeBookSourceQuality.isAcceptableChangeSourceHit(img, "新乙", requireAuthor = false)
        )
    }

    @Test
    fun emptyLatestAllowedWhenAuthorAndIntroCredible() {
        val intro = "（起点第一本万订吞噬同人，质量保证。）意外穿越到吞噬星空世界，陆青山本以为自己能够成为强者。"
        val qq = SearchBook(
            name = "吞噬星空：收徒万倍返还",
            origin = "https://novel.html5.qq.com",
            originName = "白浏览器",
            bookUrl = "https://novel.html5.qq.com/qbread/api/novel/bookInfo?resourceId=1155749461",
            author = "新乙",
            latestChapterTitle = "",
            intro = intro,
        )
        assertTrue(
            ChangeBookSourceQuality.isAcceptableChangeSourceHit(qq, "新乙", requireAuthor = false)
        )
        assertFalse(
            ChangeBookSourceQuality.isAcceptableChangeSourceHit(
                qq.copy(author = "", intro = intro),
                "新乙",
                requireAuthor = false,
            )
        )
        assertFalse(
            ChangeBookSourceQuality.isAcceptableChangeSourceHit(
                qq.copy(intro = "短"),
                "新乙",
                requireAuthor = false,
            )
        )
        // empty-latest fallback still requires local author overlap
        assertFalse(
            ChangeBookSourceQuality.isAcceptableChangeSourceHit(
                qq.copy(author = "别人"),
                "新乙",
                requireAuthor = false,
            )
        )
        assertEquals(40, intro.take(40).length)
        assertTrue(
            ChangeBookSourceQuality.hasCredibleAuthorIntro(
                qq.copy(intro = "x".repeat(40)),
                "新乙",
            )
        )
        assertFalse(
            ChangeBookSourceQuality.hasCredibleAuthorIntro(
                qq.copy(intro = "x".repeat(39)),
                "新乙",
            )
        )
    }

    @Test
    fun hostMatchIsHostOnlyNotQuerySubstring() {
        assertFalse(
            ChangeBookSourceQuality.isNonNovelSearchHost(
                "https://good.example/book?ref=https://image.baidu.com/x",
            )
        )
        assertFalse(ChangeBookSourceQuality.isNonNovelSearchHost("https://notdict.cn/book/1"))
        assertTrue(ChangeBookSourceQuality.isNonNovelSearchHost("https://www.dict.cn/hello"))
        assertEquals("image.baidu.com", ChangeBookSourceQuality.hostOf("https://image.baidu.com/a"))
    }

    @Test
    fun displayOriginNameAppendsBackend() {
        assertEquals(
            "🌞晴天小说5.0 · 69书吧",
            ChangeBookSourceQuality.displayOriginName(
                "🌞晴天小说5.0",
                "https://v1.gyks.cf/detail?book_id=x&source=69书吧",
            ),
        )
        assertEquals(
            "普通书源",
            ChangeBookSourceQuality.displayOriginName("普通书源", "https://example.com/a"),
        )
    }

    @Test
    fun prepareSearchHitDecoratesAndRejects() {
        val bad = SearchBook(
            name = "吞噬星空：收徒万倍返还",
            origin = "https://v1.gyks.cf/#小说/",
            originName = "🌞晴天小说5.0",
            bookUrl = "https://v1.gyks.cf/detail?book_id=x&source=猫眼",
            latestChapterTitle = "猫眼",
            author = "新乙",
        )
        assertFalse(ChangeBookSourceQuality.prepareSearchHitForChangeSource(bad, "新乙"))

        val good = SearchBook(
            name = "吞噬星空：收徒万倍返还",
            origin = "https://v1.gyks.cf/#小说/",
            originName = "🌞晴天小说5.0",
            bookUrl = "https://v1.gyks.cf/detail?book_id=y&source=69书吧",
            latestChapterTitle = "69书吧 第382章 一等天才，万倍返还！",
            author = "新乙",
        )
        assertTrue(ChangeBookSourceQuality.prepareSearchHitForChangeSource(good, "新乙"))
        assertEquals("🌞晴天小说5.0 · 69书吧", good.originName)
        assertEquals("第382章 一等天才，万倍返还！", good.latestChapterTitle)
    }
}
