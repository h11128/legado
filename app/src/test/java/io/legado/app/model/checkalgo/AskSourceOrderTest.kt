package io.legado.app.model.checkalgo

import io.legado.app.constant.BookSourceType
import io.legado.app.constant.BookType
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.help.book.BookSourceTypeMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RespondTimeRankTest {

    @Test
    fun successIsStrictlyBelowDefault() {
        val encoded = RespondTimeRank.encodeSuccess(2_000)
        assertEquals(2_000L, encoded)
        assertEquals(RespondTimeRank.SUCCESS, RespondTimeRank.classify(encoded))
        assertTrue(encoded < BookSource.DEFAULT_RESPOND_TIME)
    }

    @Test
    fun successClampsAtDefaultMinusOne() {
        val encoded = RespondTimeRank.encodeSuccess(BookSource.DEFAULT_RESPOND_TIME + 50_000)
        assertEquals(BookSource.DEFAULT_RESPOND_TIME - 1, encoded)
        assertEquals(RespondTimeRank.SUCCESS, RespondTimeRank.classify(encoded))
    }

    @Test
    fun failureWithZeroSpendingStillAboveDefault() {
        val encoded = RespondTimeRank.encodeFailure(
            effectiveTimeoutMs = 30_000,
            spendingMs = 0,
        )
        assertTrue(encoded > BookSource.DEFAULT_RESPOND_TIME)
        assertEquals(RespondTimeRank.FAILURE, RespondTimeRank.classify(encoded))
        assertEquals(BookSource.DEFAULT_RESPOND_TIME + 1, encoded)
    }

    @Test
    fun failureUsesEffectiveTimeoutWhenAboveDefault() {
        val encoded = RespondTimeRank.encodeFailure(
            effectiveTimeoutMs = 200_000,
            spendingMs = 10,
        )
        assertEquals(200_000L + 10 + 1, encoded)
        assertEquals(RespondTimeRank.FAILURE, RespondTimeRank.classify(encoded))
    }

    @Test
    fun unknownIsExactlyDefault() {
        assertEquals(
            RespondTimeRank.UNKNOWN,
            RespondTimeRank.classify(BookSource.DEFAULT_RESPOND_TIME),
        )
    }

    @Test
    fun encodeBranchesOnSuccessFlag() {
        assertEquals(
            RespondTimeRank.encodeSuccess(100),
            RespondTimeRank.encode(true, 100, 30_000),
        )
        assertEquals(
            RespondTimeRank.encodeFailure(30_000, 100),
            RespondTimeRank.encode(false, 100, 30_000),
        )
    }
}

class AskSourceOrderTest {

    private fun part(
        url: String,
        customOrder: Int,
        respondTime: Long,
        type: Int = BookSourceType.default,
    ) = BookSourcePart(
        bookSourceUrl = url,
        bookSourceName = url,
        customOrder = customOrder,
        respondTime = respondTime,
        bookSourceType = type,
    )

    @Test
    fun ranksSuccessUnknownFailure() {
        val a = part("A", 10, BookSource.DEFAULT_RESPOND_TIME) // unknown
        val b = part("B", 20, 2_000) // success
        val c = part("C", 30, 183_000) // failure
        val ordered = AskSourceOrder.order(listOf(a, b, c), headReserve = 0, threadCount = 32)
        assertEquals(listOf("B", "A", "C"), ordered.map { it.bookSourceUrl })
    }

    @Test
    fun headReserveKeepsCustomOrderFront() {
        val slowTop = part("slow-top", 0, 170_000)
        val fast = part("fast", 50, 100)
        val mid = part("mid", 1, 5_000)
        val ordered = AskSourceOrder.order(
            listOf(fast, mid, slowTop),
            headReserve = 1,
            threadCount = 32,
        )
        assertEquals("slow-top", ordered.first().bookSourceUrl)
        assertEquals(listOf("fast", "mid"), ordered.drop(1).map { it.bookSourceUrl })
    }

    @Test
    fun restSortedByRespondTimeWithinRank() {
        val slow = part("slow", 10, 50_000)
        val fast = part("fast", 20, 100)
        val ordered = AskSourceOrder.order(
            listOf(slow, fast),
            headReserve = 0,
            threadCount = 1,
        )
        assertEquals(listOf("fast", "slow"), ordered.map { it.bookSourceUrl })
    }

    @Test
    fun headReserveCappedByThreadCount() {
        val parts = (0 until 10).map { i ->
            part("s$i", i, 1000L + i)
        }
        val ordered = AskSourceOrder.order(parts, headReserve = 8, threadCount = 3)
        assertEquals(listOf("s0", "s1", "s2"), ordered.take(3).map { it.bookSourceUrl })
    }
}

class BookSourceTypeMapperTest {

    @Test
    fun mapsPriorityVideoOverImage() {
        val type = BookType.video or BookType.image or BookType.text
        assertEquals(BookSourceType.video, BookSourceTypeMapper.bookTypeToSourceType(type))
    }

    @Test
    fun mapsAudioAndWebFileAndDefault() {
        assertEquals(
            BookSourceType.audio,
            BookSourceTypeMapper.bookTypeToSourceType(BookType.audio),
        )
        assertEquals(
            BookSourceType.file,
            BookSourceTypeMapper.bookTypeToSourceType(BookType.webFile),
        )
        assertEquals(
            BookSourceType.default,
            BookSourceTypeMapper.bookTypeToSourceType(BookType.text),
        )
        assertEquals(
            BookSourceType.default,
            BookSourceTypeMapper.bookTypeToSourceType(0),
        )
    }

    @Test
    fun filterSameTypeKeepsMatchingOnly() {
        val sources = listOf(
            BookSourcePart(bookSourceUrl = "t", bookSourceType = BookSourceType.default),
            BookSourcePart(bookSourceUrl = "a", bookSourceType = BookSourceType.audio),
            BookSourcePart(bookSourceUrl = "v", bookSourceType = BookSourceType.video),
        )
        val filtered = BookSourceTypeMapper.filterSameType(sources, BookType.text)
        assertEquals(listOf("t"), filtered.map { it.bookSourceUrl })
        val audio = BookSourceTypeMapper.filterSameType(sources, BookType.audio)
        assertEquals(listOf("a"), audio.map { it.bookSourceUrl })
    }

    @Test
    fun filterSameTypeFallsBackWhenEmpty() {
        val sources = listOf(
            BookSourcePart(bookSourceUrl = "t", bookSourceType = BookSourceType.default),
            BookSourcePart(bookSourceUrl = "a", bookSourceType = BookSourceType.audio),
        )
        // image bit with only text/audio sources → empty filter → fall back
        val fallback = BookSourceTypeMapper.filterSameType(sources, BookType.image)
        assertEquals(listOf("t", "a"), fallback.map { it.bookSourceUrl })
    }
}

class CheckPriorityOrderTest {

    @Test
    fun checkPriorityUsesRespondTimeRank() {
        val urls = listOf("fail-fast", "success", "unknown", "missing")
        val times = mapOf(
            "fail-fast" to BookSource.DEFAULT_RESPOND_TIME + 1,
            "success" to 100L,
            "unknown" to BookSource.DEFAULT_RESPOND_TIME,
        )
        val ordered = CheckPriorityOrder.orderByPriority(urls, times)
        assertEquals(listOf("success", "unknown", "fail-fast", "missing"), ordered)
    }
}

class RespondTimeHealLogicTest {

    @Test
    fun encodeFailureUsesEffectiveTimeoutOverride() {
        val encoded = RespondTimeRank.encode(
            success = false,
            elapsedMs = 50,
            effectiveTimeoutMs = 10_000,
        )
        assertTrue(encoded > BookSource.DEFAULT_RESPOND_TIME)
        assertEquals(
            BookSource.DEFAULT_RESPOND_TIME + 50 + 1,
            encoded,
        )
    }

    @Test
    fun invalidGroupFastFailClassifiesAsFailureAfterHeal() {
        val healed = BookSource.DEFAULT_RESPOND_TIME + 1
        assertEquals(RespondTimeRank.FAILURE, RespondTimeRank.classify(healed))
        assertTrue(healed > BookSource.DEFAULT_RESPOND_TIME)
        assertEquals(RespondTimeRank.SUCCESS, RespondTimeRank.classify(200))
    }

    @Test
    fun partInvalidGroupNamesMatchBookSourceRule() {
        val dead = BookSourcePart(bookSourceGroup = "网站失效,推荐")
        assertTrue(dead.getInvalidGroupNames().isNotBlank())
        val timeout = BookSourcePart(bookSourceGroup = "校验超时")
        assertTrue(timeout.getInvalidGroupNames().isNotBlank())
        val ok = BookSourcePart(bookSourceGroup = "推荐")
        assertTrue(ok.getInvalidGroupNames().isBlank())
    }
}
