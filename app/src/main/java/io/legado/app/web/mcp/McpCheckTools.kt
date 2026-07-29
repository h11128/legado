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
            "与 debug_source / App 校验互斥。" +
            "可选布尔覆盖 CheckSource 开关（缺省=App 当前配置；任务结束后恢复）。",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("urls") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                    put("description", "要校验的 bookSourceUrl；缺省=全部书源")
                }
                putJsonObject("enabledOnly") {
                    put("type", "boolean")
                    put("description", "默认 true，只校验启用书源；修复波次应传 false")
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
                putJsonObject("checkDomain") {
                    put("type", "boolean")
                    put("description", "是否探测域名可达；修复波次默认 false")
                }
                putJsonObject("checkSearch") {
                    put("type", "boolean")
                    put("description", "是否校验搜索；修复波次默认 true")
                }
                putJsonObject("checkDiscovery") {
                    put("type", "boolean")
                    put("description", "是否校验发现；修复波次默认应传 false")
                }
                putJsonObject("checkInfo") {
                    put("type", "boolean")
                    put("description", "是否校验详情页；修复波次默认 true")
                }
                putJsonObject("checkCategory") {
                    put("type", "boolean")
                    put("description", "是否校验目录；修复波次默认 true")
                }
                putJsonObject("checkContent") {
                    put("type", "boolean")
                    put("description", "是否校验正文；修复波次默认 true")
                }
                putJsonObject("wSourceComment") {
                    put("type", "boolean")
                    put("description", "是否写入校验备注；缺省=App 当前配置")
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
                checkDomainOverride = request.arguments.bool("checkDomain"),
                checkSearchOverride = request.arguments.bool("checkSearch"),
                checkDiscoveryOverride = request.arguments.bool("checkDiscovery"),
                checkInfoOverride = request.arguments.bool("checkInfo"),
                checkCategoryOverride = request.arguments.bool("checkCategory"),
                checkContentOverride = request.arguments.bool("checkContent"),
                wSourceCommentOverride = request.arguments.bool("wSourceComment"),
            )
            McpChannelGuard.noteTool("start_check_sources")
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
            McpChannelGuard.noteTool("get_check_progress")
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
            McpChannelGuard.noteTool("stop_check_sources")
            ok(McpSourceCheckJob.stop())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            err(error.localizedMessage ?: error.toString())
        }
    }

    addTool(
        name = "reset_mcp_channel",
        description = "强制释放卡住的 debug / 批量校验通道（紧急恢复）。" +
            "正常情况请用 stop_check_sources；仅在通道占用中长期无响应时调用。",
        inputSchema = ToolSchema(
            properties = buildJsonObject {},
            required = emptyList(),
        ),
    ) { _ ->
        try {
            McpChannelGuard.noteTool("reset_mcp_channel")
            ok(McpChannelGuard.forceResetAll())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            err(error.localizedMessage ?: error.toString())
        }
    }
}
