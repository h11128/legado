package io.legado.app.model.checkalgo

import io.legado.app.data.entities.BookSource
import org.junit.Assert.assertEquals
import org.junit.Test

class AskTimeoutTest {

    @Test
    fun successUsesLongTimeout() {
        assertEquals(AskTimeout.SUCCESS_MS, AskTimeout.timeoutMs(1_000))
        assertEquals(
            AskTimeout.SUCCESS_MS,
            AskTimeout.timeoutMs(BookSource.DEFAULT_RESPOND_TIME - 1),
        )
    }

    @Test
    fun unknownUsesMediumTimeout() {
        assertEquals(
            AskTimeout.UNKNOWN_MS,
            AskTimeout.timeoutMs(BookSource.DEFAULT_RESPOND_TIME),
        )
    }

    @Test
    fun failureUsesShortTimeout() {
        assertEquals(
            AskTimeout.FAILURE_MS,
            AskTimeout.timeoutMs(BookSource.DEFAULT_RESPOND_TIME + 1),
        )
        assertEquals(AskTimeout.FAILURE_MS, AskTimeout.timeoutMs(500_000))
    }

    @Test
    fun autoChangeWidensBudget() {
        assertEquals(
            AskTimeout.UNKNOWN_MS,
            AskTimeout.autoChangeTimeoutMs(BookSource.DEFAULT_RESPOND_TIME + 1),
        )
        assertEquals(
            AskTimeout.SUCCESS_MS * 3,
            AskTimeout.autoChangeTimeoutMs(1_000),
        )
    }
}
