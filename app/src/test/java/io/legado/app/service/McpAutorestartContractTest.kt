package io.legado.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class McpAutorestartContractTest {

    @Test
    fun `user stop clears preference but failStart does not`() {
        val service = projectFile("app/src/main/java/io/legado/app/service/McpService.kt")
        assertTrue(service.contains("Do not persist mcpService=false"))
        assertTrue(service.contains("User-initiated stop: persist off"))
        assertTrue(service.contains("IntentAction.stop -> {"))
        assertTrue(service.contains("appCtx.putPrefBoolean(PreferKey.mcpService, false)"))
        assertTrue(service.contains("appCtx.putPrefBoolean(PreferKey.mcpService, true)"))
        assertTrue(service.contains("private fun failStart"))
        assertTrue(service.contains("mcp_service_start_retry"))
        assertTrue(service.contains("MAX_START_ATTEMPTS"))
        assertTrue(service.contains("START_RETRY_DELAYS_MS"))
        val failBody = service.substringAfter("private fun failStart")
            .substringBefore("private fun updateAddresses")
        assertFalse(
            "failStart must not clear PreferKey.mcpService",
            failBody.contains("putPrefBoolean(PreferKey.mcpService, false)"),
        )
        assertTrue(failBody.contains("delay(delayMs)"))
        assertTrue(
            "missing token must not spin retries",
            service.contains("failStart(getString(R.string.mcp_service_token_required), retry = false)"),
        )
        // Exhausted/transient fail (retry=true) arms watchdog; config errors (retry=false) cancel it.
        val afterAttempts = failBody.substringAfter("startAttempt < MAX_START_ATTEMPTS")
        val terminalWatchdog = afterAttempts.substringAfter("startForegroundNotification()")
            .substringAfter("startForegroundNotification()")
        assertTrue(
            Regex(
                """if\s*\(\s*retry\s*\)\s*\{\s*McpWatchdog\.schedule\(this\)\s*\}\s*else\s*\{\s*McpWatchdog\.cancel\(this\)\s*\}""",
                RegexOption.DOT_MATCHES_ALL,
            ).containsMatchIn(terminalWatchdog),
        )
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
        assertTrue(guard.contains("tryLockDebug"))
        assertTrue(guard.contains("unlockDebug"))
        assertTrue(guard.contains("\"busy\""))
        val toolServer = projectFile("app/src/main/java/io/legado/app/web/mcp/McpToolServer.kt")
        assertTrue(toolServer.contains("tryLockDebug"))
        assertTrue(toolServer.contains("unlockDebug"))
        assertFalse(
            "McpToolServer must not unlock debugMutex without a hold token",
            toolServer.contains("debugMutex.unlock()"),
        )
        val job = projectFile("app/src/main/java/io/legado/app/web/mcp/McpSourceCheckJob.kt")
        assertTrue(job.contains("Does NOT clear running"))
        val appMcp = projectFile("app/src/main/java/io/legado/app/web/mcp/McpApplication.kt")
        assertTrue(appMcp.contains("HEALTH_PATH"))
        val debug = projectFile("app/src/main/java/io/legado/app/web/mcp/McpDebugTools.kt")
        assertTrue(debug.contains("?: 90"))
        assertTrue(debug.contains("tryLockDebug"))
        val check = projectFile("app/src/main/java/io/legado/app/web/mcp/McpCheckTools.kt")
        assertTrue(check.contains("reset_mcp_channel"))
    }

    @Test
    fun `mcp promotes foreground before engine work`() {
        val service = projectFile("app/src/main/java/io/legado/app/service/McpService.kt")
        val onStart = service.substringAfter("override fun onStartCommand")
            .substringBefore("override fun onTaskRemoved")
        val fg = onStart.indexOf("promoteForegroundNotification()")
        val schedule = onStart.indexOf("scheduleUpMcpServer()")
        assertTrue("onStartCommand must promote FGS before scheduling engine", fg >= 0)
        assertTrue(schedule > fg)
        assertTrue(
            "FGS deny path must not schedule engine",
            onStart.contains("if (sticky == START_NOT_STICKY) return sticky"),
        )
        assertTrue(
            "engine bring-up must be scheduled off main",
            service.contains("private fun scheduleUpMcpServer()"),
        )
        val scheduleBody = service.substringAfter("private fun scheduleUpMcpServer()")
            .substringBefore("@Synchronized")
        assertTrue(
            "scheduleUpMcpServer must execute on a background dispatcher",
            scheduleBody.contains("execute(") && scheduleBody.contains("Dispatchers.IO"),
        )
        assertTrue(service.contains("Dispatchers.IO"))
        // Must not call requestUpMcpServer/upMcpServer synchronously from onStartCommand body.
        assertFalse(
            Regex("""else\s*->\s*requestUpMcpServer\(\)""").containsMatchIn(onStart),
        )
        assertFalse(
            Regex("""else\s*->\s*upMcpServer\(\)""").containsMatchIn(onStart),
        )
        val onCreate = service.substringAfter("override fun onCreate()")
            .substringBefore("override fun onStartCommand")
        assertTrue(
            "onCreate should also promote FGS early for BOOT/package-replaced",
            onCreate.contains("promoteForegroundNotification()"),
        )
        val base = projectFile("app/src/main/java/io/legado/app/base/BaseService.kt")
        assertTrue(
            "BaseService must expose promoteForegroundNotification for FGS subclasses",
            base.contains("protected fun promoteForegroundNotification(): Boolean"),
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
