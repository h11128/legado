package io.legado.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class McpAutorestartContractTest {

    @Test
    fun `user stop clears preference but stopWithError does not`() {
        val service = projectFile("app/src/main/java/io/legado/app/service/McpService.kt")
        assertTrue(service.contains("Do not persist mcpService=false"))
        assertTrue(service.contains("User-initiated stop: persist off"))
        assertTrue(service.contains("IntentAction.stop -> {"))
        assertTrue(service.contains("appCtx.putPrefBoolean(PreferKey.mcpService, false)"))
        assertTrue(service.contains("appCtx.putPrefBoolean(PreferKey.mcpService, true)"))
    }

    @Test
    fun `app restores mcp when preference remains true`() {
        val app = projectFile("app/src/main/java/io/legado/app/App.kt")
        assertTrue(app.contains("PreferKey.mcpService"))
        assertTrue(app.contains("McpService.start"))
    }

    @Test
    fun `settings ui does not overwrite mcp preference from isRun`() {
        val fragment = projectFile("app/src/main/java/io/legado/app/ui/main/my/MyFragment.kt")
        assertFalse(fragment.contains("putPrefBoolean(PreferKey.mcpService, McpService.isRun)"))
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
