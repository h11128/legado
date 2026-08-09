package io.legado.app.model.review

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookReviewBinding
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.ReviewRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewCapabilityTest {

    @Test
    fun jsPairDetectedByFunctionKeyword() {
        val js = """
            function getReviewSummary(chapter, book) { return []; }
            function getReviewDetail(chapter, book, paraIndex, paraData, page) { return {items:[]}; }
        """.trimIndent()
        assertTrue(ReviewCapability.declaresJsReviewPair(js))
    }

    @Test
    fun jsPairDetectedByAssignment() {
        val js = """
            var getReviewSummary = function(){};
            var getReviewDetail = function(){};
        """.trimIndent()
        assertTrue(ReviewCapability.declaresJsReviewPair(js))
    }

    @Test
    fun jsMissingHalfIsNotCapable() {
        assertFalse(ReviewCapability.declaresJsReviewPair("function getReviewSummary(){}"))
        assertFalse(ReviewCapability.declaresJsReviewPair("function getReviewDetail(){}"))
        assertFalse(ReviewCapability.declaresJsReviewPair(""))
        assertFalse(ReviewCapability.declaresJsReviewPair(null))
    }

    @Test
    fun nativeRequiresEnabledAndUrls() {
        val incomplete = ReviewRule(enabled = true, reviewSummaryUrl = "https://x/s")
        assertFalse(ReviewCapability.isNativeReviewCapable(incomplete))
        val full = ReviewRule(
            enabled = true,
            reviewSummaryUrl = "https://x/s",
            summaryListRule = "$.list",
            summaryParagraphIndexRule = "$.i",
            summaryCountRule = "$.c",
            reviewDetailUrl = "https://x/d",
            detailListRule = "$.items",
            detailContentRule = "$.body",
        )
        assertTrue(ReviewCapability.isNativeReviewCapable(full))
        assertFalse(ReviewCapability.isNativeReviewCapable(full.copy(enabled = false)))
    }

    @Test
    fun jsSourceUsesMainJsNotRuleReview() {
        val source = BookSource().apply {
            bookSourceUrl = "js://demo"
            mainJs = """
                function getReviewSummary(){}
                function getReviewDetail(){}
            """.trimIndent()
            ruleReview = null
        }
        assertTrue(ReviewCapability.isReviewCapable(source))
    }
}

class ReviewAlignConfigTest {

    @Test
    fun acceptSetMatchesRfc() {
        assertTrue(ReviewAlignConfig.acceptsChapterAlign(1.0))
        assertTrue(ReviewAlignConfig.acceptsChapterAlign(0.95))
        assertTrue(ReviewAlignConfig.acceptsChapterAlign(0.7))
        assertFalse(ReviewAlignConfig.acceptsChapterAlign(0.65))
        assertFalse(ReviewAlignConfig.acceptsChapterAlign(0.5))
        assertFalse(ReviewAlignConfig.acceptsChapterAlign(0.4))
        assertFalse(ReviewAlignConfig.acceptsChapterAlign(null))
    }
}

class ReviewOverlayResolverTest {

    private fun book(origin: String = "https://content") = Book().apply {
        name = "书"
        author = "作者"
        bookUrl = "https://content/book/1"
        this.origin = origin
    }

    private fun capableSource(url: String) = BookSource().apply {
        bookSourceUrl = url
        mainJs = "function getReviewSummary(){}\nfunction getReviewDetail(){}"
    }

    private fun binding(providerOrigin: String) = BookReviewBinding(
        contentBookUrl = "https://content/book/1",
        contentName = "书",
        contentAuthor = "作者",
        contentOrigin = "https://content",
        providerSourceUrl = providerOrigin,
        providerBookUrl = "https://review/book/1",
        providerName = "评",
        providerAuthor = "作者",
    )

    @Test
    fun prefOffIgnoresBinding() {
        val mode = ReviewOverlayResolver.resolve(
            book = book(),
            originSource = null,
            bindings = listOf(binding("https://review")),
            overlayEnabled = false,
        )
        assertEquals(ReviewOverlayMode.NativeOnly, mode)
    }

    @Test
    fun unboundWithoutOriginCapability() {
        val mode = ReviewOverlayResolver.resolve(
            book = book(),
            originSource = BookSource().apply { bookSourceUrl = "https://content" },
            bindings = null,
            overlayEnabled = true,
        )
        assertEquals(ReviewOverlayMode.Unbound, mode)
    }

    @Test
    fun nativeWhenOriginCapableAndNoBinding() {
        val origin = capableSource("https://content")
        val mode = ReviewOverlayResolver.resolve(
            book = book(origin.bookSourceUrl),
            originSource = origin,
            bindings = null,
            overlayEnabled = true,
        )
        assertEquals(ReviewOverlayMode.Native, mode)
    }

    @Test
    fun sameOriginBindingShortCircuitsNative() {
        val origin = capableSource("https://content")
        val mode = ReviewOverlayResolver.resolve(
            book = book(origin.bookSourceUrl),
            originSource = origin,
            bindings = listOf(binding(origin.bookSourceUrl)),
            overlayEnabled = true,
        )
        assertEquals(ReviewOverlayMode.Native, mode)
    }

    @Test
    fun overlayWhenBoundToOtherProvider() {
        val origin = capableSource("https://content")
        val bind = binding("https://review")
        val mode = ReviewOverlayResolver.resolve(
            book = book(origin.bookSourceUrl),
            originSource = origin,
            bindings = listOf(bind),
            overlayEnabled = true,
        )
        assertTrue(mode is ReviewOverlayMode.Overlay)
        assertEquals(listOf(bind), (mode as ReviewOverlayMode.Overlay).bindings)
    }

    @Test
    fun multiBindingsStayOverlayEvenIfOriginIncluded() {
        val origin = capableSource("https://content")
        val binds = listOf(
            binding(origin.bookSourceUrl).copy(sortOrder = 0),
            binding("https://review").copy(sortOrder = 1, providerSourceUrl = "https://review"),
        )
        val mode = ReviewOverlayResolver.resolve(
            book = book(origin.bookSourceUrl),
            originSource = origin,
            bindings = binds,
            overlayEnabled = true,
        )
        assertTrue(mode is ReviewOverlayMode.Overlay)
        assertEquals(2, (mode as ReviewOverlayMode.Overlay).bindings.size)
    }

    @Test
    fun providerStillMatchesUsesBookIdentityNotSourceLabel() {
        val content = book()
        val ok = BookReviewBinding(
            contentBookUrl = content.bookUrl,
            contentName = content.name,
            contentAuthor = content.author,
            contentOrigin = content.origin,
            providerSourceUrl = "https://review",
            providerBookUrl = "https://review/book/1",
            providerName = content.name,
            providerAuthor = content.author,
        )
        assertTrue(ReviewOverlayBindings.providerStillMatches(content, ok))
        val wrong = ok.copy(providerName = "某书源显示名")
        assertFalse(ReviewOverlayBindings.providerStillMatches(content, wrong))
    }
}
