package io.legado.app.web.mcp

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.routing
import io.legado.app.api.controller.BookSourceController
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp

fun Application.configureMcp(
    tokenProvider: () -> String?,
    unauthorizedMessage: () -> String,
    allowedHosts: List<String>,
    allowedOrigins: List<String>,
    serviceRunProvider: () -> Boolean = { true },
    serverFactory: RoutingContext.() -> Server,
) {
    intercept(ApplicationCallPipeline.Plugins) {
        context.response.header(HttpHeaders.CacheControl, "no-store")
        // Health stays token-gated like other MCP HTTP, but skips host/origin SDK checks.
        if (!BookSourceController.matchesJsSourceApiToken(
                tokenProvider(),
                context.request.header(McpAccess.TOKEN_HEADER),
            )
        ) {
            context.respondText(
                text = unauthorizedMessage(),
                status = HttpStatusCode.Unauthorized,
            )
            finish()
        }
    }
    routing {
        get(McpAccess.HEALTH_PATH) {
            call.respondText(
                text = McpChannelGuard.healthJson(serviceRunProvider()),
                contentType = ContentType.Application.Json,
            )
        }
    }
    mcpStreamableHttp(
        path = McpAccess.PATH,
        allowedHosts = allowedHosts,
        allowedOrigins = allowedOrigins,
        block = serverFactory,
    )
}
