package io.legado.app.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level contracts for RFC-001 P0a/P0b/P0c wiring.
 */
class Rfc001AskOrderContractTest {

    @Test
    fun runnerEncodesViaRespondTimeRank() {
        val runner = projectFile("app/src/main/java/io/legado/app/model/BookSourceCheckRunner.kt")
        assertTrue(runner.contains("RespondTimeRank.encode("))
        assertFalse(
            runner.contains("source.respondTime = System.currentTimeMillis() - startTime")
        )
    }

    @Test
    fun mcpStartsPerSourceDebugTiming() {
        val mcp = projectFile("app/src/main/java/io/legado/app/web/mcp/McpSourceCheckJob.kt")
        assertTrue(mcp.contains("Debug.startChecking(sessionId, source)"))
        assertTrue(mcp.contains("tryStartCheckSession()"))
    }

    @Test
    fun checkSourceServiceDoesNotOverwriteRespondTime() {
        val service = projectFile("app/src/main/java/io/legado/app/service/CheckSourceService.kt")
        assertFalse(service.contains("Debug.getRespondTime("))
        assertTrue(service.contains("Debug.startChecking(sessionId, source)"))
    }

    @Test
    fun askOrderWiredIntoLoadSites() {
        val change = projectFile(
            "app/src/main/java/io/legado/app/ui/book/changesource/ChangeBookSourceViewModel.kt"
        )
        assertTrue(change.contains("AskSourceOrder.order("))
        assertTrue(change.contains("BookSourceTypeMapper.filterSameType("))
        assertTrue(change.contains("RespondTimeUpdater.noteSuccessAndMaybeFlush("))
        assertTrue(change.contains("AskTimeout.CHANGE_SOURCE_MS"))
        assertFalse(change.contains("CheckHostTokenBucket("))
        assertFalse(change.contains("AskFailCooldown("))
        assertFalse(change.contains("AskEarlyStop."))

        val scope = projectFile("app/src/main/java/io/legado/app/ui/book/search/SearchScope.kt")
        assertTrue(scope.contains("AskSourceOrder.order("))
        assertFalse(scope.contains("sortedBy { it.customOrder }"))

        val read = projectFile("app/src/main/java/io/legado/app/ui/book/read/ReadBookViewModel.kt")
        assertTrue(read.contains("AskSourceOrder.order("))
        assertTrue(read.contains("RespondTimeUpdater.noteSuccess("))
        assertTrue(read.contains("AskTimeout.AUTO_CHANGE_MS"))
        assertFalse(read.contains("CheckHostTokenBucket("))
        assertFalse(read.contains("AskFailCooldown("))

        val search = projectFile("app/src/main/java/io/legado/app/model/webBook/SearchModel.kt")
        assertTrue(search.contains("AskTimeout.SEARCH_MS"))
        assertFalse(search.contains("CheckHostTokenBucket("))
        assertFalse(search.contains("AskFailCooldown("))
    }

    @Test
    fun healRunsFromAppStartup() {
        val app = projectFile("app/src/main/java/io/legado/app/App.kt")
        assertTrue(app.contains("respondTimeHealDone"))
        assertTrue(app.contains("ensureRespondTimeHealed()"))
        val help = projectFile("app/src/main/java/io/legado/app/help/source/SourceHelp.kt")
        assertTrue(help.contains("fun healRespondTimeEncoding()"))
        assertTrue(help.contains("fun ensureRespondTimeHealed()"))
        assertTrue(help.contains("getInvalidGroupNames()"))
        assertTrue(help.contains("getPartsWithRespondTimeBelow"))
        assertTrue(help.contains("updateRespondTime"))
        val updater = projectFile("app/src/main/java/io/legado/app/model/RespondTimeUpdater.kt")
        assertTrue(updater.contains("tryAcquireRespondTimeFlush"))
        assertTrue(updater.contains("releaseRespondTimeFlush"))
        val debug = projectFile("app/src/main/java/io/legado/app/model/Debug.kt")
        assertTrue(debug.contains("fun tryAcquireRespondTimeFlush("))
        assertTrue(debug.contains("RespondTimeUpdater.flush()"))
        assertTrue(debug.contains("respondTimeFlushHeld"))
        assertFalse(debug.contains("fun getRespondTime("))
        val search = projectFile("app/src/main/java/io/legado/app/model/webBook/SearchModel.kt")
        assertTrue(search.contains("notedRespondTimeUrls"))
        val change = projectFile(
            "app/src/main/java/io/legado/app/ui/book/changesource/ChangeBookSourceViewModel.kt"
        )
        assertTrue(change.contains("ensureRespondTimeHealed()"))
        val scope = projectFile("app/src/main/java/io/legado/app/ui/book/search/SearchScope.kt")
        assertTrue(scope.contains("ensureRespondTimeHealed()"))
        val read = projectFile("app/src/main/java/io/legado/app/ui/book/read/ReadBookViewModel.kt")
        assertTrue(read.contains("ensureRespondTimeHealed()"))
    }

    @Test
    fun daoExposesRespondTimeOnlyUpdateAndHealQuery() {
        val dao = projectFile("app/src/main/java/io/legado/app/data/dao/BookSourceDao.kt")
        assertTrue(dao.contains("fun updateRespondTime("))
        assertTrue(dao.contains("fun getPartsWithRespondTimeBelow("))
    }

    @Test
    fun applyViewToManualOrderRewritesFullTable() {
        val vm = projectFile(
            "app/src/main/java/io/legado/app/ui/book/source/manage/BookSourceViewModel.kt"
        )
        assertTrue(vm.contains("fun applyViewToManualOrder("))
        assertTrue(vm.contains("appDb.bookSourceDao.allPart"))
        val activity = projectFile(
            "app/src/main/java/io/legado/app/ui/book/source/manage/BookSourceActivity.kt"
        )
        assertTrue(activity.contains("apply_view_to_manual_order_need_clear_filter"))
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
