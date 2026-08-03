package io.legado.app.model.checkalgo

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AskFailCooldownTest {

    @Before
    fun setUp() {
        AskFailCooldown.clear()
    }

    @After
    fun tearDown() {
        AskFailCooldown.clear()
    }

    @Test
    fun shouldSkipAfterThresholdFails() {
        val url = "https://bad.example"
        assertFalse(AskFailCooldown.shouldSkip(url))
        repeat(AskFailCooldown.FAIL_THRESHOLD - 1) {
            AskFailCooldown.noteFail(url)
            assertFalse(AskFailCooldown.shouldSkip(url))
        }
        AskFailCooldown.noteFail(url)
        assertTrue(AskFailCooldown.shouldSkip(url))
        assertEquals(AskFailCooldown.FAIL_THRESHOLD, AskFailCooldown.failCount(url))
    }

    @Test
    fun noteSuccessClearsFails() {
        val url = "https://ok.example"
        repeat(AskFailCooldown.FAIL_THRESHOLD) { AskFailCooldown.noteFail(url) }
        assertTrue(AskFailCooldown.shouldSkip(url))
        AskFailCooldown.noteSuccess(url)
        assertFalse(AskFailCooldown.shouldSkip(url))
        assertEquals(0, AskFailCooldown.failCount(url))
    }

    @Test
    fun clearResetsAll() {
        AskFailCooldown.noteFail("a")
        AskFailCooldown.noteFail("b")
        AskFailCooldown.clear()
        assertEquals(0, AskFailCooldown.failCount("a"))
        assertEquals(0, AskFailCooldown.failCount("b"))
    }

    @Test
    fun unknownUrlHasZeroFails() {
        assertEquals(0, AskFailCooldown.failCount("never-seen"))
        assertFalse(AskFailCooldown.shouldSkip("never-seen"))
    }
}
