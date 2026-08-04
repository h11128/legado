package io.legado.app.model.checkalgo

import org.junit.Assert.assertEquals
import org.junit.Test

class AskTimeoutTest {

    @Test
    fun fixedBudgetsMatchPreRfcDefaults() {
        assertEquals(30_000L, AskTimeout.SEARCH_MS)
        assertEquals(60_000L, AskTimeout.CHANGE_SOURCE_MS)
        assertEquals(180_000L, AskTimeout.AUTO_CHANGE_MS)
    }
}
