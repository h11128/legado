package io.legado.app.help.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HttpCallTimingTest {

    @Test
    fun `queueMs floors total nanoseconds to millis`() {
        val acc = HttpCallTiming.Acc()
        acc.addQueueNs(999_999L)
        assertEquals(0L, acc.queueMs)
        acc.addQueueNs(1_000_000L)
        // 1_999_999 ns → 1 ms
        assertEquals(1L, acc.queueMs)
        acc.addQueueNs(2_500_000L)
        // 4_499_999 ns → 4 ms
        assertEquals(4L, acc.queueMs)
    }

    @Test
    fun `workMs formula excludes queue from content wall`() {
        val contentMs = 50_000L
        val queueMs = 47_000L
        val workMs = (contentMs - queueMs).coerceAtLeast(0L)
        assertEquals(3_000L, workMs)
    }

    @Test
    fun `okhttp client wires timing factory and first interceptor`() {
        val helper = projectFile("app/src/main/java/io/legado/app/help/http/HttpHelper.kt")
        assertTrue(helper.contains("eventListenerFactory(HttpCallTiming.eventListenerFactory)"))
        assertTrue(
            helper.indexOf("dispatcherReleaseInterceptor") <
                helper.indexOf("OkHttpExceptionInterceptor")
        )
        val utils = projectFile("app/src/main/java/io/legado/app/help/http/OkHttpUtils.kt")
        assertTrue(utils.contains("HttpCallTiming.tagRequest"))
        val vm = projectFile(
            "app/src/main/java/io/legado/app/ui/book/changesource/ChangeBookSourceViewModel.kt"
        )
        assertTrue(vm.contains("HttpCallTiming.measure"))
        assertTrue(vm.contains("queueMs="))
        assertTrue(vm.contains("workMs="))
        assertTrue(vm.contains("deep-gate-wait"))
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
