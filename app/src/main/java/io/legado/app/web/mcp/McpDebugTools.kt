package io.legado.app.web.mcp

import io.legado.app.data.appDb
import io.legado.app.model.Debug
import io.legado.app.web.mcp.McpToolSupport.err
import io.legado.app.web.mcp.McpToolSupport.int
import io.legado.app.web.mcp.McpToolSupport.ok
import io.legado.app.web.mcp.McpToolSupport.str
import io.legado.app.web.mcp.McpToolSupport.stringProp
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

private val debugScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
private val debugMutex = Mutex()

internal fun Server.registerMcpDebugTools() {
    addTool(
        name = "debug_source",
        description = "运行应用内单书源调试并返回逐步日志（单通道）。" +
            "key 可为关键词、绝对 URL、::URL、++URL 或 --URL。批量请用 start_check_sources。",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("url", stringProp("书源 bookSourceUrl"))
                put("key", stringProp("调试关键词或入口 URL"))
                putJsonObject("timeoutSec") {
                    put("type", "integer")
                    put("description", "超时秒数，默认 120，范围 10..600")
                }
            },
            required = listOf("url", "key"),
        ),
    ) { request ->
        try {
            val url = request.arguments.str("url")
                ?: return@addTool err("参数 url 不能为空")
            val key = request.arguments.str("key")
                ?: return@addTool err("参数 key 不能为空")
            val timeoutSec = (request.arguments.int("timeoutSec") ?: 120).coerceIn(10, 600)
            val source = appDb.bookSourceDao.getBookSource(url)
                ?: return@addTool err("未找到书源，请检查书源地址")
            if (!debugMutex.tryLock()) {
                return@addTool err("调试通道占用中，请稍后重试")
            }
            try {
                if (Debug.callback != null || Debug.isChecking) {
                    return@addTool err("调试通道占用中，请稍后重试")
                }
                val (log, timedOut) = McpDebugCollector().collect(
                    debugScope,
                    source,
                    key,
                    timeoutSec * 1_000L,
                )
                val body = McpFormat.truncate(log.ifEmpty { "（调试无输出）" })
                ok(
                    if (timedOut) {
                        "$body\n\n[调试超时 ${timeoutSec}s，以上为已收到的部分日志]"
                    } else {
                        body
                    },
                )
            } finally {
                debugMutex.unlock()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            err(error.localizedMessage ?: error.toString())
        }
    }
}
