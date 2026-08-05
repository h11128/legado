package io.legado.app.web.mcp

import io.legado.app.help.config.ChangeSourcePrefsApply
import io.legado.app.web.mcp.McpToolSupport.bool
import io.legado.app.web.mcp.McpToolSupport.err
import io.legado.app.web.mcp.McpToolSupport.int
import io.legado.app.web.mcp.McpToolSupport.ok
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

internal fun Server.registerMcpChangeSourceTools() {
    addTool(
        name = "set_change_source_prefs",
        description = "设置换源相关偏好（加载字数 / 足够好源后提前停止）。供 agent 自动化；与菜单「加载字数」「足够好源后提前停止」同路径。",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("loadWordCount") {
                    put("type", "boolean")
                    put("description", "是否加载字数并跑质量徽章；缺省不改")
                }
                putJsonObject("earlyStop") {
                    put("type", "boolean")
                    put("description", "是否足够好源后提前停止；缺省不改")
                }
                putJsonObject("earlyStopCount") {
                    put("type", "integer")
                    put("description", "提前停止所需质量 OK 数；缺省不改")
                }
            },
            required = emptyList(),
        ),
    ) { request ->
        try {
            val msg = ChangeSourcePrefsApply.apply(
                loadWordCount = request.arguments.bool("loadWordCount"),
                earlyStop = request.arguments.bool("earlyStop"),
                earlyStopCount = request.arguments.int("earlyStopCount"),
            )
            ok("$msg\n${ChangeSourcePrefsApply.snapshot()}")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            err(error.localizedMessage ?: error.toString())
        }
    }

    addTool(
        name = "get_change_source_prefs",
        description = "读取换源相关偏好当前值。",
        inputSchema = ToolSchema(
            properties = buildJsonObject {},
            required = emptyList(),
        ),
    ) { _ ->
        try {
            ok(ChangeSourcePrefsApply.snapshot())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            err(error.localizedMessage ?: error.toString())
        }
    }
}
