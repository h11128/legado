package io.legado.app.model.checkalgo

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
    fun contentOkIgnoresLatestMetaInHardTier() {
        val ok = ChangeBookSourceQuality.sortTier(
            chapterWordCount = 3800,
            wordCountText = "字数：3800",
            metaTiers = ChangeBookSourceQuality.TIER_LATEST_BAD,
        )
        assertEquals(ChangeBookSourceQuality.TIER_OK, ok)
        assertEquals(1, ChangeBookSourceQuality.softMetaPenalty(ChangeBookSourceQuality.TIER_LATEST_BAD))
        assertEquals(0, ChangeBookSourceQuality.softMetaPenalty(ChangeBookSourceQuality.TIER_OK))
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
}
