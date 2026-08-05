package io.legado.app.model.checkalgo

import io.legado.app.data.entities.BookSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AskTimeoutBudgetTest {

    @Test
    fun successAndUnknownKeepFullBudget() {
        assertEquals(
            AskTimeout.CHANGE_SOURCE_MS,
            AskTimeoutBudget.forChangeSourceAsk(1_000L, sessionDemoted = false),
        )
        assertEquals(
            AskTimeout.CHANGE_SOURCE_MS,
            AskTimeoutBudget.forChangeSourceAsk(
                BookSource.DEFAULT_RESPOND_TIME,
                sessionDemoted = false,
            ),
        )
    }

    @Test
    fun failureAndDemotedUseGrace() {
        val failureRt = RespondTimeRank.encodeFailure(30_000L, 100L)
        assertEquals(
            AskTimeoutBudget.GRACE_MS,
            AskTimeoutBudget.forChangeSourceAsk(failureRt, sessionDemoted = false),
        )
        assertEquals(
            AskTimeoutBudget.GRACE_MS,
            AskTimeoutBudget.forChangeSourceAsk(1_000L, sessionDemoted = true),
        )
    }
}

class TitleEmptyLedgerTest {

    @Test
    fun ttlExpires() {
        var now = 1_000L
        val ledger = TitleEmptyLedger(ttlMs = 100L, clock = { now })
        ledger.note("book", "https://a.com")
        assertTrue(ledger.isEmpty("book", "https://a.com"))
        now = 1_050L
        assertTrue(ledger.isEmpty("book", "https://a.com"))
        now = 1_200L
        assertFalse(ledger.isEmpty("book", "https://a.com"))
    }

    @Test
    fun importMergesNewer() {
        val ledger = TitleEmptyLedger(ttlMs = 10_000L, clock = { 5_000L })
        ledger.importEntries("book", mapOf("https://a.com" to 1_000L))
        assertTrue(ledger.isEmpty("book", "https://a.com"))
        ledger.importEntries("book", mapOf("https://a.com" to 4_000L))
        assertEquals(4_000L, ledger.snapshot("book")["https://a.com"])
    }
}
