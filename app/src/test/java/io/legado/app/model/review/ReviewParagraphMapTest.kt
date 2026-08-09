package io.legado.app.model.review

import io.legado.app.model.analyzeRule.ReviewRuleParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class ReviewParagraphMapTest {

    @Test
    fun splitParagraphsKeepsShortBlocks() {
        val content = "短\n\n\n中等段落内容\n\n   第三段也保留   \n\n"
        val paras = ReviewParagraphMapper.splitParagraphs(content)
        assertEquals(listOf("短", "中等段落内容", "第三段也保留"), paras)
    }

    @Test
    fun fixtureSameBodyCoverageOpensParagraphIcons() {
        // Mirrors docs/design/fixtures/rfc-004-review-provider-fixture.js § getContent/summary.
        val title = "第一章 开端"
        val paras = listOf(
            "晨光刚落在城墙上，${title}的风便带着尘土扑面而来。巡逻的士兵交换着眼神，谁也不敢先开口。",
            "驿站里传来马蹄声，信使把一卷密封文书交到守将手里。蜡印鲜红，边角还沾着雨痕。",
            "文书展开后，厅内一时静得只剩灯芯爆裂。有人低声说：北境三日内必须回信，否则粮道将断。",
            "夜色压下来时，城门仍未关闭。远处火把连成一线，像一条缓慢游动的金色蛇。",
        )
        val content = paras.joinToString("\n\n")
        val providerParas = ReviewParagraphMapper.splitParagraphs(content)
        assertEquals(4, providerParas.size)
        val localToProvider = ReviewParagraphMapper.hardMapLocalToProvider(paras, providerParas)
        assertEquals(mapOf(1 to 1, 2 to 2, 3 to 3, 4 to 4), localToProvider)
        // Fixture summary: chapter bucket + each body para has count > 0
        val counts = mapOf(-1 to 2, 1 to 1, 2 to 3, 3 to 1, 4 to 1)
        val coverage = ReviewParagraphMapper.coverage(counts, localToProvider.values.toSet())
        assertEquals(1.0, coverage, 1e-9)
        assertTrue(coverage >= ReviewAlignConfig.MAP_COVERAGE_MIN)
        assertEquals("4/4", ReviewParagraphMapper.coverageRatioLabel(counts, localToProvider.values.toSet()))
    }

    @Test
    fun hardMapMatchesIdenticalParagraphs() {
        val locals = listOf(
            "晨光刚落在城墙上，风便带着尘土扑面而来。巡逻的士兵交换着眼神。",
            "驿站里传来马蹄声，信使把一卷密封文书交到守将手里。",
            "夜色压下来时，城门仍未关闭。远处火把连成一线。",
        )
        val map = ReviewParagraphMapper.hardMapLocalToProvider(locals, locals)
        assertEquals(mapOf(1 to 1, 2 to 2, 3 to 3), map)
    }

    @Test
    fun hardMapNeverOverwritesOccupiedLocal() {
        val shared = "独有长句用于制造高相似：青铜编钟在雨夜里反复回响，回廊尽头只剩一个人的脚步。"
        val locals = listOf(shared, "完全不同的本地第二段内容甲乙丙丁戊己庚辛。")
        val providers = listOf(shared, shared)
        val map = ReviewParagraphMapper.hardMapLocalToProvider(locals, providers)
        // Both providers match local1 best; only one may claim local1.
        assertEquals(1, map.size)
        assertEquals(1, map.keys.single())
        assertTrue(map.values.single() in setOf(1, 2))
    }

    @Test
    fun hardMapIgnoresBelowThreshold() {
        val locals = listOf("甲乙丙丁戊己庚辛壬癸子丑寅卯辰巳午未申酉戌亥。")
        val providers = listOf("完全无关的另一套叙事：火车穿过隧道，广告牌闪过三遍。")
        val map = ReviewParagraphMapper.hardMapLocalToProvider(locals, providers)
        assertTrue(map.isEmpty())
    }

    @Test
    fun coverageRequiresHalfHardMapped() {
        val counts = mapOf(-1 to 2, 1 to 1, 2 to 1, 3 to 1, 4 to 1)
        assertEquals(0.5, ReviewParagraphMapper.coverage(counts, setOf(1, 2)), 1e-9)
        assertTrue(ReviewParagraphMapper.coverage(counts, setOf(1)) < ReviewAlignConfig.MAP_COVERAGE_MIN)
        assertEquals(1.0, ReviewParagraphMapper.coverage(counts, setOf(1, 2, 3, 4)), 1e-9)
        assertEquals("1/4", ReviewParagraphMapper.coverageRatioLabel(counts, setOf(1)))
    }

    @Test
    fun coverageEmptyReviewedIsOne() {
        assertEquals(1.0, ReviewParagraphMapper.coverage(mapOf(-1 to 5), emptySet()), 1e-9)
    }

    @Test
    fun remapSummaryToLocalIds() {
        val raw = ReviewRuleParser.SummaryResult(
            counts = mapOf(-1 to 2, 1 to 3, 2 to 1, 3 to 9),
            keys = mapOf(-1 to "章", 1 to "p1", 2 to "p2", 3 to "p3"),
        )
        // Local 1←provider2, local 2←provider1 (crossed)
        val remapped = ReviewParagraphMapper.remapSummaryToLocal(
            raw,
            localToProvider = mapOf(1 to 2, 2 to 1),
        )
        assertEquals(mapOf(-1 to 2, 1 to 1, 2 to 3), remapped.counts)
        assertEquals(mapOf(-1 to "章", 1 to "p2", 2 to "p1"), remapped.keys)
    }

    @Test
    fun toParaRefsSkipsMissingKeys() {
        val refs = ReviewParagraphMapper.toParaRefs(
            localToProvider = mapOf(1 to 1, 2 to 2),
            summaryKeys = mapOf(1 to "k1"),
        )
        assertEquals(1, refs.size)
        assertEquals(ProviderParaRef(1, "k1"), refs[1])
        assertFalse(refs.containsKey(2))
    }

    @Test
    fun hardMapUsesExplicitReviewIdsForMultiTitleOffset() {
        val texts = listOf(
            "晨光刚落在城墙上，风便带着尘土扑面而来。巡逻的士兵交换着眼神。",
            "驿站里传来马蹄声，信使把一卷密封文书交到守将手里。",
        )
        // Multi-line title → body reviewIds start at 2 (paragraphNum - titleOffset).
        val map = ReviewParagraphMapper.hardMapLocalToProvider(
            localTexts = texts,
            providerParas = texts,
            startReviewId = 2,
        )
        assertEquals(mapOf(2 to 1, 3 to 2), map)
    }

    @Test
    fun authorityFixtureIsContentSplitVerified() {
        assertEquals(
            ReviewParagraphAuthority.Kind.ContentSplitVerified,
            ReviewParagraphAuthority.authorityFor(ReviewParagraphAuthority.FIXTURE_PROVIDER_URL),
        )
        assertEquals(
            ReviewParagraphAuthority.Kind.ContentSplitVerified,
            ReviewParagraphAuthority.authorityFor(ReviewParagraphAuthority.QIDIAN_REVIEW_PROVIDER_URL),
        )
        assertEquals(
            ReviewParagraphAuthority.Kind.Unsupported,
            ReviewParagraphAuthority.authorityFor("https://example.com"),
        )
        assertFalse(ReviewParagraphAuthority.isParagraphMapOpen("https://other"))
        assertTrue(
            ReviewParagraphAuthority.isParagraphMapOpen(
                ReviewParagraphAuthority.FIXTURE_PROVIDER_URL,
            ),
        )
        assertTrue(
            ReviewParagraphAuthority.isParagraphMapOpen(
                ReviewParagraphAuthority.QIDIAN_REVIEW_PROVIDER_URL,
            ),
        )
        assertTrue(
            ReviewParagraphAuthority.allowsParagraphMapForContent(
                ReviewParagraphAuthority.FIXTURE_PROVIDER_URL,
                contentOrigin = "https://pirate.example/book/1",
            ),
        )
        assertTrue(
            ReviewParagraphAuthority.allowsParagraphMapForContent(
                ReviewParagraphAuthority.QIDIAN_REVIEW_PROVIDER_URL,
                contentOrigin = "https://m.qidian.com/book/1010868264/",
            ),
        )
        assertTrue(
            ReviewParagraphAuthority.allowsParagraphMapForContent(
                ReviewParagraphAuthority.QIDIAN_REVIEW_PROVIDER_URL,
                contentOrigin = "https://book.qidian.com",
            ),
        )
        assertFalse(
            ReviewParagraphAuthority.allowsParagraphMapForContent(
                ReviewParagraphAuthority.QIDIAN_REVIEW_PROVIDER_URL,
                contentOrigin = "https://www.bqg99.com/book/1",
            ),
        )
        assertFalse(
            ReviewParagraphAuthority.allowsParagraphMapForContent(
                ReviewParagraphAuthority.QIDIAN_REVIEW_PROVIDER_URL,
                contentOrigin = null,
            ),
        )
        // RuleEmittedPreview must not open content-split hard-map path.
        assertFalse(
            ReviewParagraphAuthority.isParagraphMapOpen(
                ReviewParagraphAuthority.Kind.RuleEmittedPreview,
            ),
        )
    }

    @Test
    fun hardMapAbortsWhenInactive() {
        val texts = listOf(
            "晨光刚落在城墙上，风便带着尘土扑面而来。巡逻的士兵交换着眼神。",
            "驿站里传来马蹄声，信使把一卷密封文书交到守将手里。",
        )
        val map = ReviewParagraphMapper.hardMapLocalToProvider(
            localTexts = texts,
            providerParas = texts,
            isActive = { false },
        )
        assertTrue(map.isEmpty())
    }
}
