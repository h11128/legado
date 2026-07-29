package io.legado.app.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BookSourceCheckRunnerContractTest {

    @Test
    fun `search retries up to three hits before failing deep path`() {
        val runner = projectFile("app/src/main/java/io/legado/app/model/BookSourceCheckRunner.kt")
        assertTrue(runner.contains("MAX_SEARCH_DEEP_TRIES = 3"))
        assertTrue(runner.contains("take(MAX_SEARCH_DEEP_TRIES)"))
        assertTrue(runner.contains("clearSearchDeepGroups"))
        assertTrue(runner.contains("index < books.lastIndex"))
        assertTrue(runner.contains("MAX_DISCOVERY_DEEP_TRIES = 2"))
    }

    @Test
    fun `empty download link maps to detail failure not website dead`() {
        val runner = projectFile("app/src/main/java/io/legado/app/model/BookSourceCheckRunner.kt")
        assertTrue(runner.contains("下载链接为空"))
        assertTrue(runner.contains("详情失效"))
        assertTrue(runner.contains("搜索详情失效"))
        assertTrue(runner.contains("发现详情失效"))
        assertTrue(runner.contains("搜索桌面阅读器失效") || runner.contains("\${bookType}桌面阅读器失效"))
        assertTrue(runner.contains("DesktopViewerHint"))
        assertTrue(runner.contains("consumeDesktopViewerRedirectHint"))
    }

    @Test
    fun `timeout path tags and reports 校验超时`() {
        val runner = projectFile("app/src/main/java/io/legado/app/model/BookSourceCheckRunner.kt")
        assertTrue(runner.contains("source.addGroup(\"校验超时\")"))
        assertTrue(runner.contains("is TimeoutCancellationException -> \"校验超时\""))
    }

    @Test
    fun `checkSource accepts job-local settings`() {
        val checkSource = projectFile("app/src/main/java/io/legado/app/model/CheckSource.kt")
        assertTrue(checkSource.contains("data class Settings"))
        assertTrue(checkSource.contains("fun fromGlobals()"))
        assertTrue(checkSource.contains("fun merge("))

        val runner = projectFile("app/src/main/java/io/legado/app/model/BookSourceCheckRunner.kt")
        assertTrue(runner.contains("settings: CheckSource.Settings"))
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

class CheckSourceJobLocalContractTest {

    @Test
    fun `mcp check job passes settings without mutating globals`() {
        val job = projectFile("app/src/main/java/io/legado/app/web/mcp/McpSourceCheckJob.kt")
        assertTrue(job.contains("activeSettings"))
        assertTrue(job.contains("CheckSource.Settings.merge"))
        assertTrue(job.contains("settings = settings"))
        assertFalse(job.contains("applyOverrides"))
        assertFalse(job.contains("restoreFlags"))
    }

    @Test
    fun `progress and health expose anti-block host hints`() {
        val job = projectFile("app/src/main/java/io/legado/app/web/mcp/McpSourceCheckJob.kt")
        assertTrue(job.contains("hostThrottled"))
        assertTrue(job.contains("hostEwmaLow"))
        assertTrue(job.contains("antiBlockSnapshot"))

        val guard = projectFile("app/src/main/java/io/legado/app/web/mcp/McpChannelGuard.kt")
        assertTrue(guard.contains("hostThrottled"))
        assertTrue(guard.contains("hostEwmaLow"))
        assertTrue(guard.contains("antiBlockSnapshot"))

        val bucket = projectFile("app/src/main/java/io/legado/app/model/checkalgo/CheckHostTokenBucket.kt")
        assertTrue(bucket.contains("hostsWithLowTokens"))

        val ewma = projectFile("app/src/main/java/io/legado/app/model/checkalgo/CheckHostEwma.kt")
        assertTrue(ewma.contains("hostsBelowRate"))
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
