package io.legado.app.web.mcp

import io.legado.app.api.controller.HttpLogController
import io.legado.app.help.http.HttpLogRecord
import io.legado.app.web.mcp.McpToolSupport.bool
import io.legado.app.web.mcp.McpToolSupport.dataOrThrow
import io.legado.app.web.mcp.McpToolSupport.err
import io.legado.app.web.mcp.McpToolSupport.int
import io.legado.app.web.mcp.McpToolSupport.ok
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Instant

internal fun Server.registerMcpHttpLogTools() {
    addTool(
        name = "get_http_logs",
        description = "读取最新的已脱敏 HTTP 请求日志摘要；内存最多保留 50 条。",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("limit") {
                    put("type", "integer")
                    put("description", "条数，默认 50")
                }
            },
            required = emptyList(),
        ),
    ) { request ->
        try {
            val limit = request.arguments.int("limit") ?: 50
            val data = HttpLogController.getLogs(mapOf("limit" to listOf(limit.toString())))
                .dataOrThrow() as Map<*, *>
            val recording = data["recording"] as Boolean
            val logs = data["logs"] as List<*>
            val lines = logs.map { item ->
                val log = item as Map<*, *>
                "#${log["id"]} ${Instant.ofEpochMilli(log["time"] as Long)} " +
                    "${log["method"]} ${log["url"]} -> ${log["statusCode"]} " +
                    "${log["duration"]}ms" +
                    (log["error"]?.let { " | $it" } ?: "")
            }
            val header = if (recording) {
                "最新 ${lines.size} 条："
            } else {
                "HTTP 日志记录未开启；以下为关闭前保留的记录："
            }
            ok("$header\n${lines.ifEmpty { listOf("（空）") }.joinToString("\n")}")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            err(error.localizedMessage ?: error.toString())
        }
    }

    addTool(
        name = "get_http_log",
        description = "按 id 读取单条已脱敏 HTTP 请求详情；请求和响应正文记录上限各为 8 KiB。",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("id") {
                    put("type", "integer")
                    put("description", "get_http_logs 返回的记录 id")
                }
            },
            required = listOf("id"),
        ),
    ) { request ->
        try {
            val id = request.arguments.int("id")
                ?: return@addTool err("参数 id 不能为空")
            val record = HttpLogController.getLog(mapOf("id" to listOf(id.toString())))
                .dataOrThrow() as HttpLogRecord
            ok(McpFormat.truncate(record.detail, 200_000))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            err(error.localizedMessage ?: error.toString())
        }
    }

    addTool(
        name = "set_http_log_recording",
        description = "开启或关闭应用内 HTTP 日志记录；设置会持久化，切换不会清空已有记录。",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("enabled") {
                    put("type", "boolean")
                    put("description", "true 开启，false 关闭")
                }
            },
            required = listOf("enabled"),
        ),
    ) { request ->
        try {
            val enabled = request.arguments.bool("enabled")
                ?: return@addTool err("参数 enabled 必须为布尔值")
            HttpLogController.setRecording(enabled).dataOrThrow()
            ok("HTTP 日志记录已${if (enabled) "开启" else "关闭"}")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            err(error.localizedMessage ?: error.toString())
        }
    }
}
