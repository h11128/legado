package io.legado.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source contracts for WebService FGS timing + async bind (same class of ANR as McpService).
 */
class WebServiceFgsContractTest {

    @Test
    fun `web promotes foreground before binding ports`() {
        val service = projectFile("app/src/main/java/io/legado/app/service/WebService.kt")
        val onStart = service.substringAfter("override fun onStartCommand")
            .substringBefore("override fun onDestroy")
        val fg = onStart.indexOf("promoteForegroundNotification()")
        val schedule = onStart.indexOf("scheduleUpWebServer()")
        assertTrue("onStartCommand must promote FGS before scheduling bind", fg >= 0)
        assertTrue(schedule > fg)
        assertTrue(
            "FGS deny path must not schedule bind",
            onStart.contains("if (sticky == START_NOT_STICKY) return sticky"),
        )
        assertFalse(
            "must not call upWebServer synchronously from onStartCommand",
            Regex("""else\s*->\s*upWebServer\(\)""").containsMatchIn(onStart),
        )
        assertFalse(
            Regex("""->\s*\{\s*upWebServer\(\)""").containsMatchIn(onStart),
        )

        val onCreate = service.substringAfter("override fun onCreate()")
            .substringBefore("override fun onStartCommand")
        assertTrue(
            "onCreate should promote FGS before network registration work",
            onCreate.contains("promoteForegroundNotification()"),
        )
        val fgCreate = onCreate.indexOf("promoteForegroundNotification()")
        val register = onCreate.indexOf("networkChangedListener.register()")
        assertTrue(fgCreate >= 0)
        assertTrue(register > fgCreate)
    }

    @Test
    fun `web binds ports off the main thread with backoff retry`() {
        val service = projectFile("app/src/main/java/io/legado/app/service/WebService.kt")
        assertTrue(service.contains("private fun scheduleUpWebServer()"))
        val scheduleBody = service.substringAfter("private fun scheduleUpWebServer()")
            .substringBefore("@Synchronized")
        assertTrue(
            "scheduleUpWebServer must execute on a background dispatcher",
            scheduleBody.contains("execute(") && scheduleBody.contains("Dispatchers.IO"),
        )
        assertTrue(service.contains("MAX_START_ATTEMPTS"))
        assertTrue(service.contains("START_RETRY_DELAYS_MS"))
        assertTrue(service.contains("private fun failStart"))
        assertTrue(service.contains("web_service_start_retry"))
        assertTrue(service.contains("delay(delayMs)"))
        assertTrue(service.contains("@Volatile"))
        assertTrue(
            Regex("""@Synchronized\s+override fun onDestroy\(\)""").containsMatchIn(service),
        )
        val failBody = service.substringAfter("private fun failStart")
            .substringBefore("private fun refreshAddressNotification")
        assertTrue(failBody.contains("startAttempt < MAX_START_ATTEMPTS"))
        assertTrue(failBody.contains("upWebServer()"))
    }

    @Test
    fun `base service exposes promoteForeground for subclasses`() {
        val base = projectFile("app/src/main/java/io/legado/app/base/BaseService.kt")
        assertTrue(base.contains("protected fun promoteForegroundNotification(): Boolean"))
        assertTrue(
            base.substringAfter("override fun onStartCommand")
                .substringBefore("override fun onTaskRemoved")
                .contains("promoteForegroundNotification()"),
        )
    }

    private fun projectFile(path: String): String {
        var root = File(requireNotNull(System.getProperty("user.dir")))
        repeat(6) {
            val candidate = File(root, path)
            if (candidate.isFile) return candidate.readText()
            root = root.parentFile ?: error("Project root not found for: $path")
        }
        error("Project file not found: $path")
    }
}
