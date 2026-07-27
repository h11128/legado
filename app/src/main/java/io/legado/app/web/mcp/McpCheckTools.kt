package io.legado.app.web.mcp

import io.legado.app.web.mcp.McpToolSupport.bool
import io.legado.app.web.mcp.McpToolSupport.err
import io.legado.app.web.mcp.McpToolSupport.int
import io.legado.app.web.mcp.McpToolSupport.long
import io.legado.app.web.mcp.McpToolSupport.ok
import io.legado.app.web.mcp.McpToolSupport.str
import io.legado.app.web.mcp.McpToolSupport.stringList
import io.legado.app.web.mcp.McpToolSupport.stringProp
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

internal fun Server.registerMcpCheckTools() {
    addTool(
        name = "start_check_sources",
        description = "启动多线程批量书源校验（与 App「校验书源」同逻辑）。" +
            "后台执行；用 get_check_progress 查进度，stop_check_sources 取消。" +
            "与 debug_source / App 校验互斥。",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("urls") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                    put("description", "要校验的 bookSourceUrl；缺省=全部书源")
                }
                putJsonObject("enabledOnly") {
                    put("type", "boolean")
                    put("description", "默认 true，只校验启用书源")
                }
                put("keyword", stringProp("校验搜索关键词，默认使用 App 校验配置"))
                putJsonObject("threadCount") {
                    put("type", "integer")
                    put("description", "并发线程数，默认 App 设置，最大 999")
                }
                putJsonObject("timeoutMs") {
                    put("type", "integer")
                    put("description", "单源超时毫秒，默认 App 校验超时")
                }
            },
            required = emptyList(),
        ),
    ) { request ->
        try {
            val msg = McpSourceCheckJob.start(
                urls = request.arguments.stringList("urls").ifEmpty { null },
                enabledOnly = request.arguments.bool("enabledOnly") ?: true,
                keywordOverride = request.arguments.str("keyword"),
                threadCountOverride = request.arguments.int("threadCount"),
                timeoutMsOverride = request.arguments.long("timeoutMs"),
            )
            if (msg.startsWith("已开始")) ok(msg) else err(msg)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            err(error.localizedMessage ?: error.toString())
        }
    }

    addTool(
        name = "get_check_progress",
        description = "读取 MCP 批量校验进度与结果分页。",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("resultOffset") {
                    put("type", "integer")
                    put("description", "结果偏移，默认 0")
                }
                putJsonObject("resultLimit") {
                    put("type", "integer")
                    put("description", "结果条数，默认 50，最大 500")
                }
            },
            required = emptyList(),
        ),
    ) { request ->
        try {
            val snap = McpSourceCheckJob.snapshot(
                resultOffset = request.arguments.int("resultOffset") ?: 0,
                resultLimit = request.arguments.int("resultLimit") ?: 50,
            )
            ok(McpFormat.toPrettyJson(snap))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            err(error.localizedMessage ?: error.toString())
        }
    }

    addTool(
        name = "stop_check_sources",
        description = "停止进行中的 MCP 批量校验。",
        inputSchema = ToolSchema(
            properties = buildJsonObject {},
            required = emptyList(),
        ),
    ) { _ ->
        try {
            ok(McpSourceCheckJob.stop())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            err(error.localizedMessage ?: error.toString())
        }
    }
}
