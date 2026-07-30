package io.legado.app.web.mcp

import io.legado.app.api.controller.BookSourceController
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.help.source.SourceHelp
import io.legado.app.model.jsSource.JsSourceUpsert
import io.legado.app.utils.GSON
import io.legado.app.web.mcp.McpToolSupport.bool
import io.legado.app.web.mcp.McpToolSupport.dataOrThrow
import io.legado.app.web.mcp.McpToolSupport.err
import io.legado.app.web.mcp.McpToolSupport.int
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

internal fun Server.registerMcpSourceTools() {
    addTool(
        name = "save_source",
        description = "保存单个书源。纯 JavaScript 单文件源传脚本原文；声明式源传 BookSource JSON。" +
            "默认保留已有启用状态与排序权重；分组为空时默认保留旧分组。" +
            "传入 preserveEnabled=false / preserveGroup=false / preserveOrderWeight=false 可覆盖。" +
            "（preserveOrderWeight=false 时写入 JSON 的 customOrder/weight/respondTime。）",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("source", stringProp("JS 脚本原文或 BookSource JSON 对象"))
                put("format", stringProp("js|json；缺省时自动识别"))
                putJsonObject("preserveEnabled") {
                    put("type", "boolean")
                    put("description", "默认 true；false 时使用 JSON 中的 enabled/enabledExplore")
                }
                putJsonObject("preserveGroup") {
                    put("type", "boolean")
                    put("description", "默认 true；false 时允许用空字符串清空分组")
                }
                putJsonObject("preserveOrderWeight") {
                    put("type", "boolean")
                    put("description", "默认 true；false 时写入 JSON 的 customOrder/weight/respondTime")
                }
            },
            required = listOf("source"),
        ),
    ) { request ->
        try {
            val source = request.arguments.str("source")
                ?: return@addTool err("参数 source 不能为空")
            val format = request.arguments.str("format") ?: McpFormat.detectFormat(source)
            val preserveEnabled = request.arguments.bool("preserveEnabled") ?: true
            val preserveGroup = request.arguments.bool("preserveGroup") ?: true
            val preserveOrderWeight = request.arguments.bool("preserveOrderWeight") ?: true
            when (format) {
                "js" -> {
                    val saved = BookSourceController.saveJsSource(source).dataOrThrow() as BookSource
                    ok("已保存：${saved.bookSourceName}\nbookSourceUrl: ${saved.bookSourceUrl}")
                }
                "json" -> {
                    val saved = McpSourceStore.saveDeclarative(
                        source,
                        McpSourceStore.SaveOptions(
                            preserveEnabled = preserveEnabled,
                            preserveGroupWhenBlank = preserveGroup,
                            preserveOrderWeight = preserveOrderWeight,
                        ),
                    )
                    ok(
                        "已保存：${saved.bookSourceName}\nbookSourceUrl: ${saved.bookSourceUrl}\n" +
                            "enabled=${saved.enabled} group=${saved.bookSourceGroup.orEmpty()}",
                    )
                }
                else -> err("参数 format 必须为 js 或 json")
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            err(error.localizedMessage ?: error.toString())
        }
    }

    addTool(
        name = "list_sources",
        description = "分页列出书源摘要。默认每页 100，最大 500；用 offset/limit 翻页避免截断。",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("search", stringProp("名称或 URL 子串，大小写不敏感"))
                putJsonObject("enabledOnly") {
                    put("type", "boolean")
                    put("description", "true 仅启用，false 仅禁用，缺省不过滤")
                }
                putJsonObject("offset") {
                    put("type", "integer")
                    put("description", "偏移，默认 0")
                }
                putJsonObject("limit") {
                    put("type", "integer")
                    put(
                        "description",
                        "每页条数，默认 ${McpFormat.DEFAULT_LIST_LIMIT}，最大 ${McpFormat.MAX_LIST_LIMIT}",
                    )
                }
            },
            required = emptyList(),
        ),
    ) { request ->
        try {
            val summaries = McpFormat.summarizeSources(
                sources = appDb.bookSourceDao.all,
                search = request.arguments.str("search"),
                enabledOnly = request.arguments.bool("enabledOnly"),
            )
            val page = McpFormat.pageSummaries(
                summaries = summaries,
                offset = request.arguments.int("offset") ?: 0,
                limit = request.arguments.int("limit") ?: McpFormat.DEFAULT_LIST_LIMIT,
            )
            ok(McpFormat.toPrettyJson(page))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            err(error.localizedMessage ?: error.toString())
        }
    }

    addTool(
        name = "get_source",
        description = "按 bookSourceUrl 读取书源 JSON，超长内容最多返回 200000 字符。",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("url", stringProp("书源 bookSourceUrl"))
            },
            required = listOf("url"),
        ),
    ) { request ->
        try {
            val url = request.arguments.str("url")
                ?: return@addTool err("参数 url 不能为空")
            val source = appDb.bookSourceDao.getBookSource(url)
                ?: return@addTool err("未找到书源，请检查书源地址")
            ok(McpFormat.truncate(McpFormat.prettyJson(GSON.toJson(source)), 200_000))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            err(error.localizedMessage ?: error.toString())
        }
    }

    addTool(
        name = "delete_sources",
        description = "按 bookSourceUrl 删除一个或多个书源。",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("urls") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                    put("description", "bookSourceUrl 列表")
                }
            },
            required = listOf("urls"),
        ),
    ) { request ->
        try {
            val urls = request.arguments.stringList("urls")
            if (urls.isEmpty()) return@addTool err("参数 urls 不能为空")
            JsSourceUpsert.withSaveLock {
                val existing = urls.mapNotNull(appDb.bookSourceDao::getBookSource)
                if (existing.isEmpty()) {
                    return@withSaveLock ok("未找到可删除的书源")
                }
                SourceHelp.deleteBookSources(existing)
                ok("已删除 ${existing.size} 个书源")
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            err(error.localizedMessage ?: error.toString())
        }
    }
}
