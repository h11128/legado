package io.legado.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CheckHostShardingTest {

    @Test
    fun `host sharding round-robins across hosts`() {
        val urls = listOf(
            "https://a.example/1",
            "https://a.example/2",
            "https://b.example/1",
            "https://c.example/1",
            "https://a.example/3",
        )
        val sharded = CheckHostSharding.shardByHost(urls)
        assertEquals(5, sharded.size)
        assertEquals("https://a.example/1", sharded[0])
        assertEquals("https://b.example/1", sharded[1])
        assertEquals("https://c.example/1", sharded[2])
        assertEquals("https://a.example/2", sharded[3])
        assertEquals("https://a.example/3", sharded[4])
    }
}

class CheckPerfContractTest {

    @Test
    fun `check pipeline wires CheckMode TOC sampling and batch writer`() {
        val runner = projectFile("app/src/main/java/io/legado/app/model/BookSourceCheckRunner.kt")
        assertTrue(runner.contains("CheckMode"))
        assertTrue(runner.contains("skipDiscoveryIfSearchOk"))
        assertTrue(runner.contains("CheckDnsGuard"))

        val toc = projectFile("app/src/main/java/io/legado/app/model/webBook/BookChapterList.kt")
        assertTrue(toc.contains("tocSampleChapters"))
        assertTrue(toc.contains("CheckMode.nestedConcurrency"))

        val job = projectFile("app/src/main/java/io/legado/app/web/mcp/McpSourceCheckJob.kt")
        assertTrue(job.contains("CheckSourceResultWriter"))
        assertTrue(job.contains("CheckWorkStealingScheduler") || job.contains("AIMD"))
        assertTrue(job.contains("CheckAimdLimiter"))
        assertTrue(job.contains("MAX_STORED_RESULTS"))
        assertTrue(job.contains("activeSettings"))
        assertFalse(job.contains("applyOverrides"))

        val okhttp = projectFile("app/src/main/java/io/legado/app/help/http/OkHttpUtils.kt")
        assertTrue(okhttp.contains("maxBytes"))
        assertTrue(okhttp.contains("use {") || okhttp.contains("use{"))
        assertTrue(okhttp.contains("CheckMode.current()"))

        val writer = projectFile("app/src/main/java/io/legado/app/model/CheckSourceResultWriter.kt")
        assertTrue(writer.contains("runInTransaction"))

        val webView = projectFile("app/src/main/java/io/legado/app/help/http/BackstageWebView.kt")
        assertTrue(webView.contains("DEFAULT_DELAY_MS = 900L"))
        assertTrue(webView.contains("CHECK_DELAY_MS = 800L"))
        assertTrue(webView.contains("CHECK_SETTLE_MAX_MS = 5000L"))
        assertTrue(webView.contains("checkSettleDelayMs()"))
        assertTrue(webView.contains("checkSettleMaxMs()"))
        assertTrue(webView.contains("DomSettleRunnable"))
        assertTrue(webView.contains("HTML_LENGTH_JS"))
        assertTrue(webView.contains("useDomSettle()"))
        assertTrue(webView.contains("settleGeneration"))
        assertTrue(webView.contains("startedAt == 0L"))
        assertTrue(webView.contains("removeCallbacksAndMessages"))
        assertTrue(webView.contains("MIN_STABLE_HTML_LEN"))
        assertTrue(webView.contains("if (Debug.isChecking) checkSettleDelayMs() else DEFAULT_DELAY_MS"))
        assertTrue(webView.contains("checkWebViewDelay"))
        assertTrue(webView.contains("checkWebViewMaxWait"))

        val checkConfig = projectFile("app/src/main/java/io/legado/app/ui/config/CheckSourceConfig.kt")
        assertTrue(checkConfig.contains("checkWebviewSettleMax"))
        assertTrue(checkConfig.contains("webViewSettleMaxMs"))
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
