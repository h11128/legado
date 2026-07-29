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
        assertTrue(app.contains("McpService.restoreIfEnabled"))
        assertTrue(app.contains("createNotificationChannels()"))
        val service = projectFile("app/src/main/java/io/legado/app/service/McpService.kt")
        assertTrue(service.contains("startForegroundServiceCompat"))
        assertTrue(service.contains("fun restoreIfEnabled"))
    }

    @Test
    fun `settings ui does not overwrite mcp preference from isRun`() {
        val fragment = projectFile("app/src/main/java/io/legado/app/ui/main/my/MyFragment.kt")
        assertFalse(fragment.contains("putPrefBoolean(PreferKey.mcpService, McpService.isRun)"))
        assertFalse(
            "MyFragment must not set mcp switch isChecked from isRun (clears autorestart pref)",
            Regex("""isChecked\s*=\s*McpService\.isRun""").containsMatchIn(fragment),
        )
    }

    @Test
    fun `lifecycle receiver covers boot and package replaced`() {
        val receiver = projectFile(
            "app/src/main/java/io/legado/app/receiver/McpLifecycleReceiver.kt"
        )
        assertTrue(receiver.contains("ACTION_BOOT_COMPLETED"))
        assertTrue(receiver.contains("ACTION_MY_PACKAGE_REPLACED"))
        assertTrue(receiver.contains("restoreIfEnabled"))
        assertTrue(receiver.contains("goAsync"))
        val manifest = projectFile("app/src/main/AndroidManifest.xml")
        assertTrue(manifest.contains(".receiver.McpLifecycleReceiver"))
        assertTrue(manifest.contains("android.intent.action.BOOT_COMPLETED"))
        assertTrue(manifest.contains("android.intent.action.MY_PACKAGE_REPLACED"))
        assertTrue(manifest.contains(".receiver.McpWatchdogReceiver"))
    }

    @Test
    fun `mcp survives task removed when preference enabled`() {
        val service = projectFile("app/src/main/java/io/legado/app/service/McpService.kt")
        assertTrue(service.contains("override fun onTaskRemoved"))
        assertTrue(service.contains("PreferKey.mcpService"))
        assertTrue(service.contains("McpWatchdog.schedule"))
        assertTrue(service.contains("McpWatchdog.cancel"))
        assertTrue(service.contains("nsdPublisher"))
        assertTrue(service.contains("pendingNetworkRestart") || service.contains("McpChannelGuard.isBusy"))
        assertTrue(service.contains("connectionIdleTimeoutSeconds"))
        assertTrue(service.contains("requestUpMcpServer"))
        assertTrue(service.contains("CONNECTION_IDLE_TIMEOUT_SEC"))
        val watchdog = projectFile("app/src/main/java/io/legado/app/service/McpWatchdog.kt")
        assertTrue(watchdog.contains("setAndAllowWhileIdle") || watchdog.contains("setInexactRepeating"))
        assertTrue(watchdog.contains("INTERVAL_MS"))
        val nsd = projectFile("app/src/main/java/io/legado/app/web/mcp/McpNsdPublisher.kt")
        assertTrue(nsd.contains("_legado-mcp._tcp"))
        assertTrue(nsd.contains("registerService"))
        assertTrue(nsd.contains("republish"))
        val guard = projectFile("app/src/main/java/io/legado/app/web/mcp/McpChannelGuard.kt")
        assertTrue(guard.contains("forceReleaseStale"))
        assertTrue(guard.contains("healthJson"))
        assertTrue(guard.contains("forceUnlockDebugMutex"))
        assertTrue(guard.contains("\"busy\""))
        val job = projectFile("app/src/main/java/io/legado/app/web/mcp/McpSourceCheckJob.kt")
        assertTrue(job.contains("Does NOT clear running"))
        val appMcp = projectFile("app/src/main/java/io/legado/app/web/mcp/McpApplication.kt")
        assertTrue(appMcp.contains("HEALTH_PATH"))
        val debug = projectFile("app/src/main/java/io/legado/app/web/mcp/McpDebugTools.kt")
        assertTrue(debug.contains("?: 90"))
        val check = projectFile("app/src/main/java/io/legado/app/web/mcp/McpCheckTools.kt")
        assertTrue(check.contains("reset_mcp_channel"))
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
