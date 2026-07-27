package io.legado.app.web.mcp

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import io.legado.app.data.entities.BookSource

object McpFormat {

    const val TRUNCATE_LIMIT = 100_000
    const val DEFAULT_LIST_LIMIT = 100
    const val MAX_LIST_LIMIT = 500

    private val prettyGson = GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create()

    fun detectFormat(source: String): String {
        val first = source.firstOrNull { !it.isWhitespace() && it != '\uFEFF' }
        return if (first == '{' || first == '[') "json" else "js"
    }

    fun summarizeSources(
        sources: List<BookSource>,
        search: String?,
        enabledOnly: Boolean? = null,
    ): List<Map<String, Any>> {
        var summaries = sources.map { source ->
            mapOf(
                "bookSourceName" to source.bookSourceName,
                "bookSourceUrl" to source.bookSourceUrl,
                "bookSourceGroup" to source.bookSourceGroup.orEmpty(),
                "enabled" to source.enabled,
                "isJsSource" to source.isJsSource(),
            )
        }
        if (enabledOnly != null) {
            summaries = summaries.filter { it["enabled"] == enabledOnly }
        }
        if (search.isNullOrEmpty()) return summaries
        return summaries.filter { summary ->
            (summary["bookSourceName"] as String).contains(search, ignoreCase = true) ||
                (summary["bookSourceUrl"] as String).contains(search, ignoreCase = true)
        }
    }

    fun pageSummaries(
        summaries: List<Map<String, Any>>,
        offset: Int,
        limit: Int,
    ): Map<String, Any> {
        val safeOffset = offset.coerceAtLeast(0)
        val safeLimit = limit.coerceIn(1, MAX_LIST_LIMIT)
        val page = summaries.drop(safeOffset).take(safeLimit)
        return mapOf(
            "total" to summaries.size,
            "offset" to safeOffset,
            "limit" to safeLimit,
            "count" to page.size,
            "items" to page,
        )
    }

    fun toPrettyJson(value: Any): String = prettyGson.toJson(value)

    fun prettyJson(json: String): String = prettyGson.toJson(JsonParser.parseString(json))

    fun truncate(text: String, limit: Int = TRUNCATE_LIMIT): String {
        if (text.length <= limit) return text
        return text.take(limit) + "\n…[已截断,原文 ${text.length} 字符]"
    }
}
