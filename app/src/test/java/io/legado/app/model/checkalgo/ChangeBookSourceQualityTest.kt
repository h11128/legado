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
    fun latestDifferentChapterNumIsUnknownNotMismatch() {
        assertNull(
            ChangeBookSourceQuality.latestMatchesLocal(
                "第187章 白虎不死神药跟随",
                "第200章 别的事情发生了",
            )
        )
        assertNull(
            ChangeBookSourceQuality.latestMatchesLocal(
                "第187章 白虎不死神药跟随",
                "第47章 仙府世界",
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
        assertEquals(
            0,
            ChangeBookSourceQuality.softMetaPenalty(
                ChangeBookSourceQuality.TIER_LATEST_BAD,
                chapterWordCount = -1,
            ),
        )
        assertEquals(
            1,
            ChangeBookSourceQuality.softMetaPenalty(
                ChangeBookSourceQuality.TIER_LATEST_BAD,
                chapterWordCount = 200,
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
        // Trusted + null refSim: tip-lag suppress (legacy).
        assertFalse(
            ChangeBookSourceQuality.shouldShowLatestMismatchBadge(
                chapterWordCount = 3800,
                contentRefSim = null,
                referenceTrusted = true,
            )
        )
        assertFalse(
            ChangeBookSourceQuality.shouldShowTocMismatchBadge(
                chapterWordCount = 3800,
                contentRefSim = null,
                referenceTrusted = true,
            )
        )
        assertFalse(
            ChangeBookSourceQuality.shouldShowLatestMismatchBadge(
                chapterWordCount = 0,
                contentRefSim = null,
            )
        )
        // Content-bad: do not stack soft meta on failure text.
        assertFalse(
            ChangeBookSourceQuality.shouldShowLatestMismatchBadge(
                chapterWordCount = -1,
                contentRefSim = 0.90,
            )
        )
        assertFalse(
            ChangeBookSourceQuality.shouldShowTocMismatchBadge(
                chapterWordCount = -1,
                contentRefSim = 0.90,
            )
        )
        assertTrue(
            ChangeBookSourceQuality.shouldShowLatestMismatchBadge(
                chapterWordCount = 3800,
                contentRefSim = 0.10,
            )
        )
        // Quality-OK: TOC never (even with weak refSim).
        assertFalse(
            ChangeBookSourceQuality.shouldShowTocMismatchBadge(
                chapterWordCount = 3800,
                contentRefSim = 0.10,
                referenceTrusted = true,
            )
        )
        assertEquals(
            0,
            ChangeBookSourceQuality.softMetaPenalty(
                ChangeBookSourceQuality.TIER_TOC_BAD,
                chapterWordCount = 0,
            ),
        )
        // Untrusted short local: latest tip is remaining signal; TOC stays off.
        assertTrue(
            ChangeBookSourceQuality.shouldShowLatestMismatchBadge(
                chapterWordCount = 3800,
                contentRefSim = null,
                referenceTrusted = false,
            )
        )
        assertFalse(
            ChangeBookSourceQuality.shouldShowTocMismatchBadge(
                chapterWordCount = 3800,
                contentRefSim = null,
                referenceTrusted = false,
            )
        )
        // Weak body: latest yes; TOC only when trusted.
        assertTrue(
            ChangeBookSourceQuality.shouldShowLatestMismatchBadge(
                chapterWordCount = 200,
                contentRefSim = null,
                referenceTrusted = false,
            )
        )
        assertFalse(
            ChangeBookSourceQuality.shouldShowTocMismatchBadge(
                chapterWordCount = 200,
                contentRefSim = null,
                referenceTrusted = false,
            )
        )
        assertTrue(
            ChangeBookSourceQuality.shouldShowTocMismatchBadge(
                chapterWordCount = 200,
                contentRefSim = null,
                referenceTrusted = true,
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
    fun earlyStopUsesThresholdAndPlateau() {
        assertEquals(
            ChangeBookSourceQuality.EarlyStopDecision.None,
            ChangeBookSourceQuality.shouldEarlyStop(19, enabled = true, target = 20),
        )
        assertEquals(
            ChangeBookSourceQuality.EarlyStopDecision.Target,
            ChangeBookSourceQuality.shouldEarlyStop(20, enabled = true, target = 20),
        )
        assertEquals(
            ChangeBookSourceQuality.EarlyStopDecision.None,
            ChangeBookSourceQuality.shouldEarlyStop(100, enabled = false, target = 20),
        )
        assertEquals(
            ChangeBookSourceQuality.EarlyStopDecision.Plateau,
            ChangeBookSourceQuality.shouldEarlyStop(
                usefulCount = 5,
                enabled = true,
                target = 20,
                completedAsks = 200,
                lastUsefulAtCompleted = 50,
            ),
        )
        assertEquals(
            ChangeBookSourceQuality.EarlyStopDecision.None,
            ChangeBookSourceQuality.shouldEarlyStop(
                usefulCount = 4,
                enabled = true,
                target = 20,
                completedAsks = 200,
                lastUsefulAtCompleted = 50,
            ),
        )
        assertEquals(
            ChangeBookSourceQuality.EarlyStopDecision.Target,
            ChangeBookSourceQuality.shouldEarlyStop(
                usefulCount = 20,
                enabled = true,
                target = 20,
                completedAsks = 200,
                lastUsefulAtCompleted = 50,
            ),
        )
        assertEquals(
            ChangeBookSourceQuality.EarlyStopDecision.None,
            ChangeBookSourceQuality.shouldEarlyStop(
                usefulCount = 5,
                enabled = true,
                target = 20,
                completedAsks = 199,
                lastUsefulAtCompleted = 50,
                plateauAsks = 150,
            ),
        )
        assertEquals(
            ChangeBookSourceQuality.EarlyStopDecision.None,
            ChangeBookSourceQuality.shouldEarlyStop(
                usefulCount = 5,
                enabled = true,
                target = 20,
                completedAsks = 500,
                lastUsefulAtCompleted = 50,
                plateauAsks = 0,
            ),
        )
    }

    @Test
    fun earlyStopUsefulVerdictIsOkOrWeak() {
        assertTrue(
            ChangeBookSourceQuality.isEarlyStopUsefulVerdict(
                ChangeBookSourceQuality.QualityVerdict.Ok,
            ),
        )
        assertTrue(
            ChangeBookSourceQuality.isEarlyStopUsefulVerdict(
                ChangeBookSourceQuality.QualityVerdict.Weak,
            ),
        )
        assertFalse(
            ChangeBookSourceQuality.isEarlyStopUsefulVerdict(
                ChangeBookSourceQuality.QualityVerdict.TooShort,
            ),
        )
        assertFalse(ChangeBookSourceQuality.isEarlyStopUsefulVerdict(null))
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
        // Short site/backend labels without source= must not count as chapters
        assertFalse(ChangeBookSourceQuality.hasUsableSearchLatest("猫眼", null))
        assertFalse(ChangeBookSourceQuality.hasUsableSearchLatest("百度", null))
        assertTrue(ChangeBookSourceQuality.hasUsableSearchLatest("一等天才万倍返还的漫长之路", null))
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
    fun nonNovelHostRejectedWhenFilterOn() {
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
        assertTrue(
            ChangeBookSourceQuality.isAcceptableChangeSourceHit(
                img,
                "新乙",
                requireAuthor = false,
                filterNonNovelHost = false,
            )
        )
    }

    @Test
    fun emptyLatestAndEmptyAuthorAllowedWhenAuthorCheckOff() {
        val hit = SearchBook(
            name = "学霸也开挂",
            origin = "http://www.biduju.net",
            originName = "必读居",
            bookUrl = "http://www.biduju.net/txtbook/176452.html",
            author = "",
            latestChapterTitle = "",
        )
        // Many sources omit author/latest on search — do not drop when toggle is off.
        assertTrue(
            ChangeBookSourceQuality.isAcceptableChangeSourceHit(hit, "", requireAuthor = false)
        )
        assertTrue(
            ChangeBookSourceQuality.isAcceptableChangeSourceHit(hit, null, requireAuthor = false)
        )
        // Toggle on + local author set → empty hit author rejected
        assertFalse(
            ChangeBookSourceQuality.isAcceptableChangeSourceHit(hit, "手握寸关尺", requireAuthor = true)
        )
    }

    @Test
    fun authorOverlapOnlyWhenToggleOn() {
        val hit = SearchBook(
            name = "吞噬星空：收徒万倍返还",
            origin = "https://www.example-novel.com",
            bookUrl = "https://www.example-novel.com/book/1",
            author = "别人",
            latestChapterTitle = "第880章 一亿纪元",
        )
        assertTrue(
            ChangeBookSourceQuality.isAcceptableChangeSourceHit(hit, "新乙", requireAuthor = false)
        )
        assertFalse(
            ChangeBookSourceQuality.isAcceptableChangeSourceHit(hit, "新乙", requireAuthor = true)
        )
        assertTrue(
            ChangeBookSourceQuality.isAcceptableChangeSourceHit(
                hit.copy(author = "新乙"),
                "新乙",
                requireAuthor = true,
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
        // Empty author + chrome tip: still accepted when author-check off; tip stripped.
        val backendOnlyNoAuthor = SearchBook(
            name = "吞噬星空：收徒万倍返还",
            origin = "https://v1.gyks.cf/#小说/",
            originName = "🌞晴天小说5.0",
            bookUrl = "https://v1.gyks.cf/detail?book_id=x&source=猫眼",
            latestChapterTitle = "猫眼",
            author = "",
        )
        assertTrue(
            ChangeBookSourceQuality.prepareSearchHitForChangeSource(
                backendOnlyNoAuthor,
                "新乙",
                requireAuthor = false,
            )
        )
        assertNull(backendOnlyNoAuthor.latestChapterTitle)
        assertEquals("🌞晴天小说5.0 · 猫眼", backendOnlyNoAuthor.originName)

        val backendOnlyWithAuthor = SearchBook(
            name = "吞噬星空：收徒万倍返还",
            origin = "https://v1.gyks.cf/#小说/",
            originName = "🌞晴天小说5.0",
            bookUrl = "https://v1.gyks.cf/detail?book_id=x&source=猫眼",
            latestChapterTitle = "猫眼",
            author = "新乙",
        )
        assertTrue(
            ChangeBookSourceQuality.prepareSearchHitForChangeSource(backendOnlyWithAuthor, "新乙")
        )
        assertNull(backendOnlyWithAuthor.latestChapterTitle)
        assertEquals("🌞晴天小说5.0 · 猫眼", backendOnlyWithAuthor.originName)

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

    @Test
    fun rejectsDictIntroEvenOnNovelHost() {
        val hit = SearchBook(
            name = "吞噬星空：收徒万倍返还",
            origin = "https://www.example-novel.com",
            originName = "某小说站",
            bookUrl = "https://www.example-novel.com/book/1",
            author = "新乙",
            latestChapterTitle = "",
            intro = "该词条未找到_海词词典",
        )
        assertFalse(
            ChangeBookSourceQuality.isAcceptableChangeSourceHit(hit, "新乙", requireAuthor = false)
        )
        assertTrue(
            ChangeBookSourceQuality.isAcceptableChangeSourceHit(
                hit,
                "新乙",
                requireAuthor = false,
                filterNonBookIntro = false,
            )
        )
    }

    @Test
    fun menuTogglesIndependently() {
        val shell = SearchBook(
            name = "吞噬星空：收徒万倍返还",
            origin = "http://dict.cn",
            originName = "海词",
            bookUrl = "http://dict.cn/%E5%90%9E%E5%99%AC",
            author = "别人",
            latestChapterTitle = "第1章",
            intro = "该词条未找到_海词词典",
        )
        // Both filters off + author check off → shell allowed through gate
        assertTrue(
            ChangeBookSourceQuality.isAcceptableChangeSourceHit(
                shell,
                "新乙",
                requireAuthor = false,
                filterNonNovelHost = false,
                filterNonBookIntro = false,
            )
        )
        // Host filter alone still blocks
        assertFalse(
            ChangeBookSourceQuality.isAcceptableChangeSourceHit(
                shell,
                "新乙",
                requireAuthor = false,
                filterNonNovelHost = true,
                filterNonBookIntro = false,
            )
        )
        // Intro filter alone still blocks when host filter is off
        assertFalse(
            ChangeBookSourceQuality.isAcceptableChangeSourceHit(
                shell.copy(origin = "https://www.example-novel.com", bookUrl = "https://www.example-novel.com/1"),
                "新乙",
                requireAuthor = false,
                filterNonNovelHost = false,
                filterNonBookIntro = true,
            )
        )
    }

    @Test
    fun placeholderAuthorIsNotCredibleSignal() {
        for (placeholder in listOf("佚名", "无名氏", "作者不详", "未知", "unknown")) {
            val hit = SearchBook(
                name = "书",
                origin = "https://www.example-novel.com",
                bookUrl = "https://www.example-novel.com/book/1",
                author = placeholder,
                latestChapterTitle = "",
            )
            assertFalse(
                "placeholder=$placeholder",
                ChangeBookSourceQuality.hasCredibleAuthorSignal(hit, "新乙"),
            )
            // Gate itself does not require author signal when toggle is off
            assertTrue(
                ChangeBookSourceQuality.isAcceptableChangeSourceHit(hit, "新乙", requireAuthor = false)
            )
        }
    }

    @Test
    fun shortBackendTipStrippedEvenWhenAccepted() {
        val hit = SearchBook(
            name = "吞噬星空：收徒万倍返还",
            origin = "https://www.example-novel.com",
            bookUrl = "https://www.example-novel.com/book/1",
            author = "",
            latestChapterTitle = "猫眼",
        )
        assertTrue(
            ChangeBookSourceQuality.isAcceptableChangeSourceHit(hit, "新乙", requireAuthor = false)
        )
        assertTrue(ChangeBookSourceQuality.prepareSearchHitForChangeSource(hit, "新乙"))
        assertNull(hit.latestChapterTitle)
        assertFalse(ChangeBookSourceQuality.hasUsableSearchLatest("猫眼", null))
    }

    @Test
    fun smartScorePendingIsNotReady() {
        assertEquals(
            -1,
            ChangeBookSourceQuality.smartScore(
                measuredChars = 0,
                verdict = ChangeBookSourceQuality.QualityVerdict.Pending,
            ),
        )
        assertEquals(-1, ChangeBookSourceQuality.smartScore(0, null))
    }

    @Test
    fun smartScoreOrdersOkAboveTooShortAboveHijack() {
        val ok = ChangeBookSourceQuality.smartScore(
            measuredChars = 3800,
            verdict = ChangeBookSourceQuality.QualityVerdict.Ok,
            contentRefSim = 0.7,
            respondTimeMs = 500,
        )
        val tooShort = ChangeBookSourceQuality.smartScore(
            measuredChars = 53,
            verdict = ChangeBookSourceQuality.QualityVerdict.TooShort,
            respondTimeMs = 300,
        )
        val hijack = ChangeBookSourceQuality.smartScore(
            measuredChars = 2500,
            verdict = ChangeBookSourceQuality.QualityVerdict.Hijack,
            respondTimeMs = 400,
        )
        assertTrue("ok=$ok tooShort=$tooShort", ok > tooShort)
        assertTrue("tooShort=$tooShort hijack=$hijack", tooShort > hijack)
        assertTrue(ok in 0..100)
        assertTrue(tooShort in 0..100)
        assertTrue(hijack in 0..100)
    }

    @Test
    fun smartScoreUserLikeRaisesAndDislikeLowers() {
        val base = ChangeBookSourceQuality.smartScore(
            measuredChars = 2000,
            verdict = ChangeBookSourceQuality.QualityVerdict.Ok,
        )
        val liked = ChangeBookSourceQuality.smartScore(
            measuredChars = 2000,
            verdict = ChangeBookSourceQuality.QualityVerdict.Ok,
            userScore = 1,
        )
        val disliked = ChangeBookSourceQuality.smartScore(
            measuredChars = 2000,
            verdict = ChangeBookSourceQuality.QualityVerdict.Ok,
            userScore = -1,
        )
        assertTrue(liked > base)
        assertTrue(disliked < base)
    }

    @Test
    fun smartScoreSpreadsOkTierByLengthAndRespondTime() {
        // Mirrors temp/change_source_scores_2026-08-08.json Ok crowd that all scored 82.
        val longFast = ChangeBookSourceQuality.smartScore(
            measuredChars = 11_595,
            verdict = ChangeBookSourceQuality.QualityVerdict.Ok,
            latestMatch = null,
            respondTimeMs = 631,
        )
        val midFast = ChangeBookSourceQuality.smartScore(
            measuredChars = 2493,
            verdict = ChangeBookSourceQuality.QualityVerdict.Ok,
            latestMatch = null,
            respondTimeMs = 223,
        )
        val midSlow = ChangeBookSourceQuality.smartScore(
            measuredChars = 2317,
            verdict = ChangeBookSourceQuality.QualityVerdict.Ok,
            latestMatch = null,
            respondTimeMs = 4005,
        )
        val shortOk = ChangeBookSourceQuality.smartScore(
            measuredChars = 1680,
            verdict = ChangeBookSourceQuality.QualityVerdict.Ok,
            latestMatch = null,
            respondTimeMs = 545,
        )
        assertTrue("longFast=$longFast midFast=$midFast", longFast > midFast)
        assertTrue("midFast=$midFast shortOk=$shortOk", midFast > shortOk)
        assertTrue("shortOk=$shortOk midSlow=$midSlow", shortOk > midSlow)
        val span = longFast - midSlow
        assertTrue("Ok span should be meaningful, span=$span", span >= 15)
    }

    @Test
    fun smartScoreDemotesWrongBookDespiteLongBody() {
        val wrongLong = ChangeBookSourceQuality.smartScore(
            measuredChars = 11_595,
            verdict = ChangeBookSourceQuality.QualityVerdict.Ok,
            latestMatch = false,
            respondTimeMs = 631,
        )
        val sameBookMid = ChangeBookSourceQuality.smartScore(
            measuredChars = 2493,
            verdict = ChangeBookSourceQuality.QualityVerdict.Ok,
            latestMatch = true,
            respondTimeMs = 400,
        )
        assertTrue(
            "wrongLong=$wrongLong should rank below sameBookMid=$sameBookMid",
            wrongLong < sameBookMid,
        )
        assertTrue("wrongLong=$wrongLong should stay mid/low", wrongLong <= 55)
    }

    @Test
    fun smartScoreRewardsMatchingLatestTip() {
        val matched = ChangeBookSourceQuality.smartScore(
            measuredChars = 2493,
            verdict = ChangeBookSourceQuality.QualityVerdict.Ok,
            latestMatch = true,
            respondTimeMs = 400,
        )
        val unknown = ChangeBookSourceQuality.smartScore(
            measuredChars = 2493,
            verdict = ChangeBookSourceQuality.QualityVerdict.Ok,
            latestMatch = null,
            respondTimeMs = 400,
        )
        assertTrue(matched > unknown)
    }

    @Test
    fun lengthSmartBonusIsMonotonicInMeasuredChars() {
        val a = ChangeBookSourceQuality.lengthSmartBonus(1680)
        val b = ChangeBookSourceQuality.lengthSmartBonus(2493)
        val c = ChangeBookSourceQuality.lengthSmartBonus(11_595)
        assertTrue(a < b && b < c)
    }

    @Test
    fun tocSizeSmartBonusRewardsMoreChaptersOverAbsentExpectation() {
        val few = ChangeBookSourceQuality.tocSizeSmartBonus(108)
        val many = ChangeBookSourceQuality.tocSizeSmartBonus(2200)
        assertTrue("few=$few many=$many", few < many)
    }

    @Test
    fun tocSizeSmartBonusPenalizesFarBehindReaderProgress() {
        // Reader already has ~2200 chapters locally; a 108-chapter hit is stale/incomplete.
        val stale = ChangeBookSourceQuality.tocSizeSmartBonus(108, expectedTocChapterCount = 2200)
        val caughtUp = ChangeBookSourceQuality.tocSizeSmartBonus(2250, expectedTocChapterCount = 2200)
        assertTrue("stale=$stale should be penalized", stale < 0)
        assertTrue("caughtUp=$caughtUp stale=$stale", caughtUp > stale)
    }

    @Test
    fun smartScoreDoesNotLetAFewerChapterSourceBeatAMoreCompleteOne() {
        // Session evidence (无限恐怖之诸天入侵): a 108-chapter hit outranked hits with far
        // more chapters and word count, because total chapter count was displayed but
        // never fed into the score. Same verdict/respondTime here — only tocChapterCount
        // and the reader's already-known progress differ.
        val fewChapters = ChangeBookSourceQuality.smartScore(
            measuredChars = 2000,
            verdict = ChangeBookSourceQuality.QualityVerdict.Ok,
            respondTimeMs = 400,
            tocChapterCount = 108,
            expectedTocChapterCount = 2200,
        )
        val manyChapters = ChangeBookSourceQuality.smartScore(
            measuredChars = 2000,
            verdict = ChangeBookSourceQuality.QualityVerdict.Ok,
            respondTimeMs = 400,
            tocChapterCount = 2500,
            expectedTocChapterCount = 2200,
        )
        assertTrue(
            "fewChapters=$fewChapters should rank below manyChapters=$manyChapters",
            fewChapters < manyChapters,
        )
    }

    @Test
    fun metricLineKeepsWordCountSeparateFromQuality() {
        val line = ChangeBookSourceQuality.metricLine(53, 320)
        assertTrue(line.contains("字数：53"))
        assertFalse(line.contains("过短"))
        assertFalse(line.contains("劫持"))
    }

    @Test
    fun metricLinePutsTotalInBracketsBeforeProbeTitle() {
        val line = ChangeBookSourceQuality.metricLine(
            measuredChars = 11595,
            respondTimeMs = 1500,
            tocChapterCount = 189,
            chapterOrdinal = 53,
            chapterTitle = "乱仑系列（未删节）",
        )
        assertTrue(line.startsWith("[189] 乱仑系列（未删节）\n"))
        assertTrue(line.contains("字数：11595"))
        assertTrue(line.contains("1.5s"))
        assertFalse(line.contains("[53]"))
        assertFalse(line.contains("共"))
    }

    @Test
    fun metricLineFallsBackToOrdinalBracketsWithoutToc() {
        val line = ChangeBookSourceQuality.metricLine(
            measuredChars = 11595,
            respondTimeMs = 1500,
            chapterOrdinal = 53,
            chapterTitle = "乱仑系列（未删节）",
        )
        assertTrue(line.startsWith("[53] 乱仑系列（未删节）\n"))
    }

    @Test
    fun catalogLineIsLatestOnly() {
        assertEquals(
            "最新：第187章 白虎",
            ChangeBookSourceQuality.catalogLine("最新：第187章 白虎"),
        )
        assertEquals("无最新章节", ChangeBookSourceQuality.catalogLine("  "))
    }

    @Test
    fun withTotalChapterBracketPrefixesBody() {
        assertEquals("[189]", ChangeBookSourceQuality.totalChapterBracket(189))
        assertEquals(
            "[189]\n字数：11595 · 1.5s",
            ChangeBookSourceQuality.withTotalChapterBracket(189, "字数：11595 · 1.5s"),
        )
        assertEquals(
            "[189] 乱仑系列\n字数：1",
            ChangeBookSourceQuality.withTotalChapterBracket(189, "[189] 乱仑系列\n字数：1"),
        )
        // Prefix must not treat `[18]` as already covering `[189]`.
        assertEquals(
            "[18]\n[189] 乱仑系列",
            ChangeBookSourceQuality.withTotalChapterBracket(18, "[189] 乱仑系列"),
        )
    }

    @Test
    fun composeProbeEvidenceKeepsPendingTextAndPrefixesTotal() {
        assertEquals(
            "[189]\n校验中…",
            ChangeBookSourceQuality.composeProbeEvidence(
                tocChapterCount = 189,
                chapterWordCount = 0,
                respondTimeMs = -1,
                chapterWordCountText = "校验中…",
            ),
        )
    }

    @Test
    fun composeProbeEvidenceKeepsCachedHeadWithoutSessionToc() {
        assertEquals(
            "[53] 乱仑系列\n字数：11595 · 1.5s",
            ChangeBookSourceQuality.composeProbeEvidence(
                tocChapterCount = 0,
                chapterWordCount = 11595,
                respondTimeMs = 1500,
                chapterWordCountText = "[53] 乱仑系列\n字数：11595 · 1.5s",
            ),
        )
    }

    @Test
    fun contentSortTierUsesVerdictWhenMeasuredCharsLookOk() {
        // Hijack with long body must still sort as content-bad via verdict.
        assertEquals(
            ChangeBookSourceQuality.TIER_CONTENT_BAD,
            ChangeBookSourceQuality.contentSortTier(
                chapterWordCount = 2500,
                verdict = ChangeBookSourceQuality.QualityVerdict.Hijack,
            ),
        )
        assertEquals(
            ChangeBookSourceQuality.TIER_OK,
            ChangeBookSourceQuality.contentSortTier(
                chapterWordCount = 2500,
                verdict = ChangeBookSourceQuality.QualityVerdict.Ok,
            ),
        )
    }

    @Test
    fun isContentBadVerdictMatchesDropFilter() {
        assertTrue(
            ChangeBookSourceQuality.isContentBadVerdict(
                ChangeBookSourceQuality.QualityVerdict.TooShort,
            ),
        )
        assertFalse(
            ChangeBookSourceQuality.isContentBadVerdict(
                ChangeBookSourceQuality.QualityVerdict.Ok,
            ),
        )
        assertFalse(ChangeBookSourceQuality.isContentBadVerdict(null))
    }

    @Test
    fun sortSmartScoreKeyKeepsPendingAboveContentBad() {
        val pending = ChangeBookSourceQuality.sortSmartScoreKey(
            -1,
            ChangeBookSourceQuality.QualityVerdict.Pending,
        )
        val tooShort = ChangeBookSourceQuality.sortSmartScoreKey(
            40,
            ChangeBookSourceQuality.QualityVerdict.TooShort,
        )
        val ok = ChangeBookSourceQuality.sortSmartScoreKey(
            82,
            ChangeBookSourceQuality.QualityVerdict.Ok,
        )
        assertTrue(ok > pending)
        assertTrue(pending > tooShort)
    }

    @Test
    fun needsSessionQualityHydrationWhenVerdictMissing() {
        assertTrue(
            ChangeBookSourceQuality.needsSessionQualityHydration(
                null,
                "字数：53 · 0.3s",
            ),
        )
        assertFalse(
            ChangeBookSourceQuality.needsSessionQualityHydration(
                ChangeBookSourceQuality.QualityVerdict.TooShort,
                "字数：53 · 0.3s",
            ),
        )
        assertFalse(ChangeBookSourceQuality.needsSessionQualityHydration(null, null))
    }

    @Test
    fun softMetaSuppressedForContentBadVerdictEvenWithPositiveChars() {
        assertFalse(
            ChangeBookSourceQuality.shouldShowLatestMismatchBadge(
                chapterWordCount = 53,
                contentRefSim = null,
                verdict = ChangeBookSourceQuality.QualityVerdict.TooShort,
            ),
        )
        assertEquals(
            0,
            ChangeBookSourceQuality.softMetaPenalty(
                ChangeBookSourceQuality.TIER_LATEST_BAD,
                chapterWordCount = 53,
                verdict = ChangeBookSourceQuality.QualityVerdict.TooShort,
            ),
        )
    }

    @Test
    fun tocTitleAffinityUnknownWhenEitherSideEmpty() {
        assertEquals(
            -1.0,
            ChangeBookSourceQuality.tocTitleAffinity(emptyList(), listOf("第1章 序")),
            0.0,
        )
        assertEquals(
            -1.0,
            ChangeBookSourceQuality.tocTitleAffinity(listOf("第1章 序"), emptyList()),
            0.0,
        )
    }

    @Test
    fun tocIdentityFalseWhenTitlesDiverge() {
        val local = (1..80).map { "第${it}章 白虎线剧情$it" }
        val junk = listOf(
            "乱仑系列（未删节）",
            "交易(校园NP，高H，全C)",
            "窑子开张了(H)",
            "被合租糙汉室友肏到哭",
        ) + (1..20).map { "快穿诱行第${it}章" }
        assertEquals(
            false,
            ChangeBookSourceQuality.tocIdentity(
                localTotal = 189,
                candidateTotal = junk.size,
                localTitles = local,
                candidateTitles = junk,
            ),
        )
    }

    @Test
    fun tocIdentityFalseWhenMidAffinityButSizeBandFails() {
        // Controlled mid affinity: only chapters 1 & 35 share exact bodies with cand (~2/12),
        // other local bodies share almost no digrams with junk titles; size band fails.
        val local = (1..189).map { i ->
            when (i) {
                1 -> "第1章 白虎开场戏码甲"
                35 -> "第35章 青龙中段剧情乙"
                else -> "第${i}章 紫薇后半独有戊己庚$i"
            }
        }
        val cand = listOf(
            "第1章 白虎开场戏码甲",
            "第35章 青龙中段剧情乙",
        ) + (1..52).map { "乱仑系列垃圾标题辛壬$it" }
        val affinity = ChangeBookSourceQuality.tocTitleAffinity(local, cand)
        assertTrue(
            "affinity=$affinity expected mid band [${ChangeBookSourceQuality.TOC_TITLE_AFFINITY_BAD}," +
                "${ChangeBookSourceQuality.TOC_TITLE_AFFINITY_OK})",
            affinity >= ChangeBookSourceQuality.TOC_TITLE_AFFINITY_BAD &&
                affinity < ChangeBookSourceQuality.TOC_TITLE_AFFINITY_OK,
        )
        assertFalse(ChangeBookSourceQuality.tocConsistent(189, cand.size))
        assertEquals(
            false,
            ChangeBookSourceQuality.tocIdentity(
                localTotal = 189,
                candidateTotal = cand.size,
                localTitles = local,
                candidateTitles = cand,
            ),
        )
    }

    @Test
    fun tocIdentityTrueForTruncatedSameBookHighAffinity() {
        // Short pirate TOC that still shares almost all sampled titles ⇒ true despite size fail.
        val local = (1..60).map { "第${it}章 白虎线剧情$it" }
        val cand = (1..48).map { "第${it}章 白虎线剧情$it" }
        assertEquals(
            true,
            ChangeBookSourceQuality.tocIdentity(
                localTotal = 189,
                candidateTotal = cand.size,
                localTitles = local,
                candidateTitles = cand,
            ),
        )
    }

    @Test
    fun tocIdentityNullWhenTitlesMissingButSizeOk() {
        assertEquals(
            null,
            ChangeBookSourceQuality.tocIdentity(
                localTotal = 100,
                candidateTotal = 90,
                localTitles = emptyList(),
                candidateTitles = emptyList(),
            ),
        )
    }

    @Test
    fun tocIdentityFalseWhenTitlesMissingAndSizeBandFails() {
        assertEquals(
            false,
            ChangeBookSourceQuality.tocIdentity(
                localTotal = 189,
                candidateTotal = 54,
                localTitles = emptyList(),
                candidateTitles = emptyList(),
            ),
        )
    }

    @Test
    fun tocTitleAffinityIgnoresBareChapterNumBodies() {
        val local = listOf("第1章", "第2章", "第3章")
        val cand = listOf("第10章", "第20章", "第30章")
        assertEquals(
            0.0,
            ChangeBookSourceQuality.tocTitleAffinity(local, cand),
            0.0,
        )
    }

    @Test
    fun smartScoreTocMatchDemotesStrongly() {
        val base = ChangeBookSourceQuality.smartScore(
            measuredChars = 2493,
            verdict = ChangeBookSourceQuality.QualityVerdict.Ok,
            latestMatch = null,
            tocMatch = null,
            respondTimeMs = 400,
        )
        val badToc = ChangeBookSourceQuality.smartScore(
            measuredChars = 2493,
            verdict = ChangeBookSourceQuality.QualityVerdict.Ok,
            latestMatch = null,
            tocMatch = false,
            respondTimeMs = 400,
        )
        val okToc = ChangeBookSourceQuality.smartScore(
            measuredChars = 2493,
            verdict = ChangeBookSourceQuality.QualityVerdict.Ok,
            latestMatch = true,
            tocMatch = true,
            respondTimeMs = 400,
        )
        assertTrue("badToc=$badToc should be below base=$base by ≥ toc penalty",
            base - badToc >= ChangeBookSourceQuality.WRONG_BOOK_TOC_PENALTY)
        assertTrue("badToc=$badToc okToc=$okToc", badToc < okToc)
        // Length bonus must be capped when tocMatch=false (same as latest mismatch).
        val uncappedLen = ChangeBookSourceQuality.smartScore(
            measuredChars = 11595,
            verdict = ChangeBookSourceQuality.QualityVerdict.Ok,
            tocMatch = null,
            respondTimeMs = 400,
        )
        val cappedLen = ChangeBookSourceQuality.smartScore(
            measuredChars = 11595,
            verdict = ChangeBookSourceQuality.QualityVerdict.Ok,
            tocMatch = false,
            respondTimeMs = 400,
        )
        assertTrue(
            "uncapped=$uncappedLen capped=$cappedLen",
            uncappedLen - cappedLen >= ChangeBookSourceQuality.WRONG_BOOK_TOC_PENALTY,
        )
    }
}

