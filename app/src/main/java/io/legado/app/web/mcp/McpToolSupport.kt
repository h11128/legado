package io.legado.app.web.mcp

import io.legado.app.api.ReturnData
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal object McpToolSupport {

    fun ok(text: String) = CallToolResult(content = listOf(TextContent(text)))

    fun err(text: String) = CallToolResult(
        content = listOf(TextContent(text)),
        isError = true,
    )

    fun ReturnData.dataOrThrow(): Any? {
        if (!isSuccess) throw IllegalArgumentException(errorMsg)
        return data
    }

    fun JsonObject?.str(key: String): String? =
        this?.get(key)?.jsonPrimitive?.contentOrNull

    fun JsonObject?.int(key: String): Int? =
        this?.get(key)?.jsonPrimitive?.intOrNull

    fun JsonObject?.long(key: String): Long? =
        this?.get(key)?.jsonPrimitive?.longOrNull

    fun JsonObject?.bool(key: String): Boolean? =
        this?.get(key)?.jsonPrimitive?.booleanOrNull

    fun JsonObject?.stringList(key: String): List<String> {
        val raw = this?.get(key) as? JsonArray ?: return emptyList()
        return raw.mapNotNull { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    fun stringProp(description: String) = buildJsonObject {
        put("type", "string")
        put("description", description)
    }
}
