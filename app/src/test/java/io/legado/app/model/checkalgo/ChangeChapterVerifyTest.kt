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
