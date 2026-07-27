package io.legado.app.model.checkalgo

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class CheckHedgedProbeTest {

    @Test
    fun `primary success cancels fallback path`() = runBlocking {
        val fallbackRuns = AtomicInteger(0)
        val result = CheckHedgedProbe.hedged(
            primaryDelayMs = 200,
            primary = {
                delay(20)
                "ok"
            },
            fallback = {
                fallbackRuns.incrementAndGet()
                "fb"
            },
        )
        assertEquals("ok", result)
        delay(250)
        assertEquals(0, fallbackRuns.get())
    }

    @Test
    fun `primary failure still uses fallback`() = runBlocking {
        val result = CheckHedgedProbe.hedged(
            primaryDelayMs = 30,
            primary = { error("primary down") },
            fallback = { "recovered" },
        )
        assertEquals("recovered", result)
    }

    @Test
    fun `slow primary loses to faster fallback`() = runBlocking {
        val result = CheckHedgedProbe.hedged(
            primaryDelayMs = 20,
            primary = {
                delay(300)
                "late"
            },
            fallback = { "fast" },
        )
        assertEquals("fast", result)
    }

    @Test
    fun `both failures surface an exception`() = runBlocking {
        var thrown = false
        try {
            CheckHedgedProbe.hedged(
                primaryDelayMs = 10,
                primary = { error("a") },
                fallback = { error("b") },
            )
        } catch (t: Throwable) {
            thrown = true
            assertTrue(t.message == "b" || t.message == "a")
        }
        assertTrue(thrown)
    }
}
