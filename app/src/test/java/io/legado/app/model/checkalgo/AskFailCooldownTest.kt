package io.legado.app.model.checkalgo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AskFailCooldownTest {

    private lateinit var cooldown: AskFailCooldown

    @Before
    fun setUp() {
        cooldown = AskFailCooldown()
    }

    @Test
    fun shouldSkipAfterThresholdFails() {
        val url = "https://bad.example"
        assertFalse(cooldown.shouldSkip(url))
        repeat(AskFailCooldown.FAIL_THRESHOLD - 1) {
            cooldown.noteFail(url)
            assertFalse(cooldown.shouldSkip(url))
        }
        cooldown.noteFail(url)
        assertTrue(cooldown.shouldSkip(url))
        assertEquals(AskFailCooldown.FAIL_THRESHOLD, cooldown.failCount(url))
    }

    @Test
    fun noteSuccessClearsFails() {
        val url = "https://ok.example"
        repeat(AskFailCooldown.FAIL_THRESHOLD) { cooldown.noteFail(url) }
        assertTrue(cooldown.shouldSkip(url))
        cooldown.noteSuccess(url)
        assertFalse(cooldown.shouldSkip(url))
        assertEquals(0, cooldown.failCount(url))
    }

    @Test
    fun clearResetsAll() {
        cooldown.noteFail("a")
        cooldown.noteFail("b")
        cooldown.clear()
        assertEquals(0, cooldown.failCount("a"))
        assertEquals(0, cooldown.failCount("b"))
    }

    @Test
    fun unknownUrlHasZeroFails() {
        assertEquals(0, cooldown.failCount("never-seen"))
        assertFalse(cooldown.shouldSkip("never-seen"))
    }

    @Test
    fun instancesDoNotShareState() {
        val other = AskFailCooldown()
        repeat(AskFailCooldown.FAIL_THRESHOLD) { cooldown.noteFail("x") }
        assertTrue(cooldown.shouldSkip("x"))
        assertFalse(other.shouldSkip("x"))
    }
}
