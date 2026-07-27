package io.legado.app.web.mcp

import io.legado.app.constant.AppConst
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities

object McpToolServer {

    fun create(): Server {
        return Server(
            serverInfo = Implementation(
                name = "legado",
                version = AppConst.appInfo.versionName,
            ),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = false),
                ),
            ),
        ).also { server ->
            server.registerMcpSourceTools()
            server.registerMcpDebugTools()
            server.registerMcpCheckTools()
            server.registerMcpHttpLogTools()
        }
    }
}
