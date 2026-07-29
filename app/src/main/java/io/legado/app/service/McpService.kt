package io.legado.app.service

import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.receiver.NetworkChangedListener
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.postEvent
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.sendToClip
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.startForegroundServiceCompat
import io.legado.app.utils.stopService
import io.legado.app.utils.toastOnUi
import io.legado.app.web.mcp.McpAccess
import io.legado.app.web.mcp.McpChannelGuard
import io.legado.app.web.mcp.McpNsdPublisher
import io.legado.app.web.mcp.McpToolServer
import io.legado.app.web.mcp.configureMcp
import splitties.init.appCtx

class McpService : BaseService() {

    companion object {
        @Volatile
        var isRun = false

        @Volatile
        var hostAddress = ""

        private const val ACTION_RESTART = "restartMcpService"
        private const val CONNECTION_IDLE_TIMEOUT_SEC = 180

        /** Start as FGS so package-replaced / background restore is allowed on Oreo+. */
        fun start(context: Context) {
            val intent = Intent(context, McpService::class.java)
            context.startForegroundServiceCompat(intent)
        }

        fun restart(context: Context) {
            val intent = Intent(context, McpService::class.java).apply {
                action = ACTION_RESTART
            }
            context.startForegroundServiceCompat(intent)
        }

        /** Resume after process death / APK update when the user left MCP enabled. */
        fun restoreIfEnabled(context: Context) {
            if (isRun) return
            if (!context.getPrefBoolean(PreferKey.mcpService, false)) return
            start(context)
        }

        fun stop(context: Context) {
            // User-initiated stop: persist off so App does not auto-restart.
            appCtx.putPrefBoolean(PreferKey.mcpService, false)
            McpWatchdog.cancel(context)
            context.stopService<McpService>()
        }
    }

    private var engine: EmbeddedServer<*, *>? = null
    private var activeAddressKeys: List<String> = emptyList()
    @Volatile
    private var destroyed = false
    private var notificationList = mutableListOf(appCtx.getString(R.string.service_starting))
    private val nsdPublisher by lazy { McpNsdPublisher(this) }
    private val networkChangedListener by lazy {
        NetworkChangedListener(this, includeDetailedChanges = true)
    }

    override fun onCreate() {
        super.onCreate()
        destroyed = false
        McpChannelGuard.onBecameIdle = {
            synchronized(this) {
                if (!destroyed && McpChannelGuard.pendingNetworkRestart && !McpChannelGuard.isBusy()) {
                    McpChannelGuard.pendingNetworkRestart = false
                    upMcpServer()
                }
            }
        }
        networkChangedListener.onNetworkChanged = {
            synchronized(this) {
                if (!destroyed) {
                    val addresses = NetworkUtils.getLocalIPAddress()
                    if (isRun) {
                        val addressKeys = addresses.mapNotNull { it.hostAddress }.sorted()
                        if (addressKeys != activeAddressKeys) {
                            // Do not stop CIO while debug/check holds the channel — that is a
                            // primary hang mode from thread 59f4efb9 (mid-tool engine restart).
                            if (McpChannelGuard.isBusy()) {
                                McpChannelGuard.pendingNetworkRestart = true
                                updateAddresses(addresses)
                            } else {
                                requestUpMcpServer()
                            }
                        }
                    } else {
                        updateAddresses(addresses)
                    }
                }
            }
        }
        networkChangedListener.register()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            IntentAction.stop -> {
                appCtx.putPrefBoolean(PreferKey.mcpService, false)
                McpWatchdog.cancel(this)
                stopSelf()
            }
            "copyHostAddress" -> sendToClip(hostAddress)
            ACTION_RESTART -> requestUpMcpServer()
            else -> requestUpMcpServer()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    /**
     * BaseService stops all services when the task is swiped away.
     * Keep MCP alive while the user left PreferKey.mcpService enabled.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (getPrefBoolean(PreferKey.mcpService, false)) {
            return
        }
        super.onTaskRemoved(rootIntent)
    }

    @Synchronized
    override fun onDestroy() {
        destroyed = true
        isRun = false
        McpChannelGuard.onBecameIdle = null
        McpChannelGuard.pendingNetworkRestart = false
        networkChangedListener.unRegister()
        nsdPublisher.unpublish()
        stopEngine()
        hostAddress = ""
        activeAddressKeys = emptyList()
        postEvent(EventBus.MCP_SERVICE, "")
        super.onDestroy()
    }

    /** Defer engine restart while debug/check holds the channel (thread 59f4efb9). */
    @Synchronized
    private fun requestUpMcpServer() {
        if (destroyed) return
        if (McpChannelGuard.isBusy()) {
            McpChannelGuard.pendingNetworkRestart = true
            return
        }
        upMcpServer()
    }

    @Synchronized
    private fun upMcpServer() {
        if (destroyed) return
        // Re-check under the same lock: a tool may have started since idle notify.
        if (McpChannelGuard.isBusy()) {
            McpChannelGuard.pendingNetworkRestart = true
            return
        }
        val token = AppConfig.jsSourceApiToken
        if (token.isNullOrBlank()) {
            stopWithError(getString(R.string.mcp_service_token_required))
            return
        }

        stopEngine()
        nsdPublisher.unpublish()
        val addresses = NetworkUtils.getLocalIPAddress()
        val port = getPort()
        val allowedHosts = McpAccess.allowedHosts(addresses)
        val allowedOrigins = McpAccess.allowedOrigins(allowedHosts)
        try {
            val nextEngine = embeddedServer(CIO, configure = {
                connector {
                    this.port = port
                    this.host = "0.0.0.0"
                }
                connectionIdleTimeoutSeconds = CONNECTION_IDLE_TIMEOUT_SEC
            }) {
                configureMcp(
                    tokenProvider = { AppConfig.jsSourceApiToken },
                    unauthorizedMessage = {
                        this@McpService.getString(R.string.mcp_service_token_invalid)
                    },
                    allowedHosts = allowedHosts,
                    allowedOrigins = allowedOrigins,
                    serviceRunProvider = { isRun },
                ) {
                    McpToolServer.create()
                }
            }
            nextEngine.start(wait = false)
            engine = nextEngine
            isRun = true
            // Keep user intent: crash/restart must not clear this.
            appCtx.putPrefBoolean(PreferKey.mcpService, true)
            activeAddressKeys = addresses.mapNotNull { it.hostAddress }.sorted()
            updateAddresses(addresses, port)
            nsdPublisher.republish(port)
            McpWatchdog.schedule(this)
            McpChannelGuard.pendingNetworkRestart = false
        } catch (error: Exception) {
            error.printOnDebug()
            stopWithError(error.localizedMessage ?: getString(R.string.mcp_service_start_failed))
        }
    }

    private fun stopEngine() {
        engine?.stop(500, 1_000)
        engine = null
    }

    private fun stopWithError(message: String) {
        isRun = false
        nsdPublisher.unpublish()
        // Do not persist mcpService=false — only user stop clears the preference.
        toastOnUi(message)
        stopSelf()
    }

    private fun updateAddresses(
        addresses: List<java.net.InetAddress> = NetworkUtils.getLocalIPAddress(),
        port: Int = getPort(),
    ) {
        notificationList = McpAccess.endpointUrls(addresses, port).toMutableList()
        hostAddress = notificationList.first()
        startForegroundNotification()
        postEvent(EventBus.MCP_SERVICE, hostAddress)
    }

    private fun getPort(): Int {
        return AppConfig.mcpPort.takeIf { it in 1024..65530 } ?: 1236
    }

    override fun startForegroundNotification() {
        val builder = NotificationCompat.Builder(this, AppConst.channelIdWeb)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSmallIcon(R.drawable.ic_web_service_noti)
            .setOngoing(true)
            .setContentTitle(getString(R.string.mcp_service))
            .setContentText(notificationList.joinToString("\n"))
            .setContentIntent(servicePendingIntent<McpService>("copyHostAddress"))
        builder.addAction(
            R.drawable.ic_stop_black_24dp,
            getString(R.string.cancel),
            servicePendingIntent<McpService>(IntentAction.stop),
        )
        startForeground(NotificationId.McpService, builder.build())
    }
}
