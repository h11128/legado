package io.legado.app.help.http

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CheckHttpLimitsContractTest {

    @Test
    fun `http helper exposes check dispatcher limit helpers`() {
        val helper = projectFile("app/src/main/java/io/legado/app/help/http/HttpHelper.kt")
        assertTrue(helper.contains("fun configureCheckHttpLimits"))
        assertTrue(helper.contains("fun restoreDefaultHttpLimits"))
        assertTrue(helper.contains("dispatcher.maxRequests"))
        assertTrue(helper.contains("dispatcher.maxRequestsPerHost"))
    }

    @Test
    fun `mcp and app check apply and restore http limits`() {
        val mcp = projectFile("app/src/main/java/io/legado/app/web/mcp/McpSourceCheckJob.kt")
        assertTrue(mcp.contains("configureCheckHttpLimits"))
        assertTrue(mcp.contains("restoreDefaultHttpLimits"))
        assertTrue(mcp.contains("allUrls()") || mcp.contains("allEnabledUrls()"))
        assertTrue(mcp.contains("MAX_STORED_RESULTS"))
        assertTrue(mcp.contains("Debug.startChecking(sessionId, source)"))
        assertFalse(Regex("""bookSourceDao\.all(?![A-Za-z])""").containsMatchIn(mcp))

        val service = projectFile("app/src/main/java/io/legado/app/service/CheckSourceService.kt")
        assertTrue(service.contains("configureCheckHttpLimits"))
        assertTrue(service.contains("restoreDefaultHttpLimits"))
        assertTrue(service.contains("Debug.startChecking(sessionId, source)"))
    }

    @Test
    fun `change-source raises and restores shared OkHttp limits`() {
        val vm = projectFile(
            "app/src/main/java/io/legado/app/ui/book/changesource/ChangeBookSourceViewModel.kt"
        )
        assertTrue(vm.contains("configureCheckHttpLimits"))
        assertTrue(vm.contains("restoreDefaultHttpLimits"))
        assertTrue(vm.contains("raiseHttpLimitsForSearch"))
        assertTrue(vm.contains("restoreHttpLimitsIfNeeded"))
        assertTrue(vm.contains("httpLimitsEpoch"))
        assertTrue(vm.contains("http-limits raised"))
        assertTrue(vm.contains("contentMs="))
        // Must not restore in stopSearch (deep-wait still needs raised caps).
        val stopIdx = vm.indexOf("fun stopSearch()")
        assertTrue(stopIdx >= 0)
        val stopBlock = vm.substring(stopIdx, stopIdx + 800)
        assertFalse(stopBlock.contains("restoreHttpLimitsIfNeeded"))
        assertFalse(stopBlock.contains("restoreDefaultHttpLimits"))
        // Restore after deep-wait, in finally, with generation check.
        assertTrue(vm.contains("pending.joinAll()"))
        assertTrue(vm.contains("restoreHttpLimitsIfNeeded(httpLimitsEpoch)"))
        assertTrue(vm.contains("http-limits skip restore"))
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
