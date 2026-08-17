package io.legado.app.model.checkalgo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangeSourceLatestConsensusTest {

    @Test
    fun tipsAgreeSameTitle() {
        assertEquals(
            true,
            ChangeSourceLatestConsensus.tipsAgree(
                "第875章 离开，黑人抬棺！震撼的宇宙海强者",
                "第875章 离开，黑人抬棺！震撼的宇宙海强者",
            ),
        )
    }

    @Test
    fun tipsAgreeSameNumberUnlikeBodyIsMismatch() {
        assertEquals(
            false,
            ChangeSourceLatestConsensus.tipsAgree(
                "第460章 这有些犯规了吧！",
                "第460章 交易校园NP高H全错书插入广告",
            ),
        )
    }

    @Test
    fun tipsAgreeDifferentNumberIsUnknown() {
        assertNull(
            ChangeSourceLatestConsensus.tipsAgree(
                "第187章 白虎不死神药跟随",
                "第200章 别的事情发生了",
            ),
        )
        assertNull(
            ChangeSourceLatestConsensus.tipsAgree(
                "第187章 白虎不死神药跟随",
                "第47章 仙府世界",
            ),
        )
    }

    @Test
    fun tipsAgreeUnnumberedJunkVsChapter() {
        assertEquals(
            false,
            ChangeSourceLatestConsensus.tipsAgree(
                "第875章 离开，黑人抬棺",
                "交易(校园NP，高H，全C)",
            ),
        )
        assertNull(ChangeSourceLatestConsensus.tipsAgree(null, "第1章"))
    }

    @Test
    fun clusterFlagsUnnumberedJunkNotPeers() {
        val titles = mapOf(
            "a" to "第875章 离开，黑人抬棺！震撼的宇宙海强者",
            "b" to "第875章 离开，黑人抬棺！震撼的宇宙海强者",
            "c" to "第875章 离开，黑人抬棺！震撼的宇宙海强者",
            "bad" to "交易(校园NP，高H，全C)",
        )
        val outliers = ChangeSourceLatestConsensus.identityOutliers(
            titlesByOrigin = titles,
            localLatest = "第875章 离开，黑人抬棺！震撼的宇宙海强者",
        )
        assertTrue(outliers.contains("bad"))
        assertFalse(outliers.contains("a"))
    }

    @Test
    fun clusterKeepsNewerChapterOfSameBook() {
        val titles = mapOf(
            "a" to "第187章 白虎不死神药跟随",
            "b" to "第187章 白虎不死神药跟随",
            "c" to "第187章 白虎不死神药跟随",
            "newer" to "第200章 别的事情发生了",
        )
        val outliers = ChangeSourceLatestConsensus.identityOutliers(
            titlesByOrigin = titles,
            localLatest = "第187章 白虎不死神药跟随",
        )
        assertFalse(outliers.contains("newer"))
        assertFalse(outliers.contains("a"))
    }

    @Test
    fun clusterKeepsSameTitleFarBehindAsProgress() {
        val titles = mapOf(
            "a" to "第187章 白虎不死神药跟随",
            "b" to "第187章 白虎不死神药跟随",
            "c" to "第187章 白虎不死神药跟随",
            "old" to "第47章 白虎不死神药跟随",
        )
        val outliers = ChangeSourceLatestConsensus.identityOutliers(
            titlesByOrigin = titles,
            localLatest = "第187章 白虎不死神药跟随",
        )
        assertFalse(outliers.contains("old"))
        assertFalse(outliers.contains("a"))
    }

    @Test
    fun clusterFlagsFarBehindIsland() {
        val titles = mapOf(
            "a" to "第187章 白虎不死神药跟随",
            "b" to "第187章 白虎不死神药跟随",
            "c" to "第187章 白虎不死神药跟随",
            "island" to "第47章 仙府世界",
        )
        val outliers = ChangeSourceLatestConsensus.identityOutliers(
            titlesByOrigin = titles,
            localLatest = "第187章 白虎不死神药跟随",
        )
        assertTrue(outliers.contains("island"))
        assertFalse(outliers.contains("a"))
    }

    @Test
    fun clusterDoesNotUseLocalAsRuler() {
        val titles = mapOf(
            "a" to "第47章 仙府世界",
            "b" to "第47章 仙府世界",
            "c" to "第47章 仙府世界",
            "d" to "第47章 仙府世界",
        )
        val outliers = ChangeSourceLatestConsensus.identityOutliers(
            titlesByOrigin = titles,
            localLatest = "第187章 白虎不死神药跟随",
        )
        assertTrue(outliers.isEmpty())
    }

    @Test
    fun clusterKeepsSplitProgressFactions() {
        val titles = mapOf(
            "a" to "第187章 白虎不死神药跟随",
            "b" to "第187章 白虎不死神药跟随",
            "c" to "第187章 白虎不死神药跟随",
            "d" to "第200章 别的事情发生了",
            "e" to "第200章 别的事情发生了",
            "f" to "第200章 别的事情发生了",
        )
        val outliers = ChangeSourceLatestConsensus.identityOutliers(titlesByOrigin = titles)
        assertTrue(outliers.isEmpty())
    }

    @Test
    fun clusterFlagsSameNumberUnlikeTitle() {
        val titles = mapOf(
            "a" to "第460章 这有些犯规了吧！",
            "b" to "第460章 这有些犯规了吧！",
            "c" to "第460章 这有些犯规了吧！",
            "bad" to "第460章 交易校园NP高H全错书插入广告",
        )
        val outliers = ChangeSourceLatestConsensus.identityOutliers(titlesByOrigin = titles)
        assertTrue(outliers.contains("bad"))
        assertFalse(outliers.contains("a"))
    }

    @Test
    fun clusterWaitsForMinSamples() {
        val titles = mapOf(
            "a" to "第187章 白虎不死神药跟随",
            "bad" to "交易(校园NP，高H，全C)",
        )
        val consensus = ChangeSourceLatestConsensus.identityConsensus(titlesByOrigin = titles)
        assertFalse(consensus.decided)
        assertTrue(consensus.outliers.isEmpty())
    }

    @Test
    fun clusterUnnumberedIsolateIsOutlier() {
        val titles = mapOf(
            "a" to "交易(校园NP，高H，全C)",
            "b" to "交易(校园NP，高H，全C)",
            "c" to "交易(校园NP，高H，全C)",
            "other" to "详细",
        )
        val consensus = ChangeSourceLatestConsensus.identityConsensus(titlesByOrigin = titles)
        assertTrue(consensus.decided)
        assertTrue(consensus.outliers.contains("other"))
        assertFalse(consensus.outliers.contains("a"))
    }

    @Test
    fun clusterNumberedWithoutIdentityFactionIsUndecided() {
        val titles = mapOf(
            "a" to "第187章 白虎不死神药跟随",
            "b" to "交易甲",
            "c" to "详细乙",
        )
        val consensus = ChangeSourceLatestConsensus.identityConsensus(titlesByOrigin = titles)
        assertFalse(consensus.decided)
        assertTrue(consensus.outliers.isEmpty())
    }

    @Test
    fun clusterAllUnnumberedNoFactionIsUndecided() {
        val titles = mapOf(
            "a" to "交易甲",
            "b" to "详细乙",
            "c" to "目录丙",
        )
        val consensus = ChangeSourceLatestConsensus.identityConsensus(titlesByOrigin = titles)
        assertFalse(consensus.decided)
        assertTrue(consensus.outliers.isEmpty())
    }
}
