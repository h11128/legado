package io.legado.app.model.webBook

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WebBookRedirectLogContractTest {

    @Test
    fun `check redirect logs desktop viewer hint`() {
        val source = projectFile("app/src/main/java/io/legado/app/model/webBook/WebBook.kt")
        assertTrue(source.contains("≡检测到重定向(\$redirectCode) → \$finalUrl"))
        assertTrue(
            source.contains("◇提示: 重定向到 data:/桌面阅读器类页面，手机规则可能读不到正文"),
        )
        assertTrue(source.contains("looksLikeDesktopViewerRedirect"))
        assertTrue(source.contains("ComicView"))
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
