package io.legado.app.service

import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
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
import io.legado.app.utils.applyPromotedProgress
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.postEvent
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.sendToClip
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.startForegroundServiceCompat
import io.legado.app.utils.startService
import io.legado.app.utils.stopService
import io.legado.app.utils.toastOnUi
import io.legado.app.web.mcp.McpAccess
import io.legado.app.web.mcp.McpChannelGuard
import io.legado.app.web.mcp.McpNsdPublisher
import io.legado.app.web.mcp.McpToolServer
import io.legado.app.web.mcp.configureMcp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import splitties.init.appCtx
import splitties.systemservices.notificationManager

class McpService : BaseService() {

    companion object {
        private const val TERMINAL_NOTIFICATION_DURATION = 4_500L

        @Volatile
        var isRun = false

        @Volatile
        var hostAddress = ""

        private const val ACTION_RESTART = "restartMcpService"
        private const val CONNECTION_IDLE_TIMEOUT_SEC = 180
        private const val MAX_START_ATTEMPTS = 5
        private val START_RETRY_DELAYS_MS = longArrayOf(2_000, 5_000, 10_000, 20_000, 30_000)

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
            if (isRun) {
                context.startService<McpService> {
                    action = IntentAction.stop
                }
            } else {
                context.stopService<McpService>()
            }
        }
    }

    private var engine: EmbeddedServer<*, *>? = null
    private var activeAddressKeys: List<String> = emptyList()
    @Volatile
    private var destroyed = false
    @Volatile
    private var stopping = false
    private var terminalStopJob: Job? = null
    private var notificationList = mutableListOf(appCtx.getString(R.string.service_starting))
    private var startAttempt = 0
    private val nsdPublisher by lazy { McpNsdPublisher(this) }
    private val networkChangedListener by lazy {
        NetworkChangedListener(this, includeDetailedChanges = true)
    }

    override fun onCreate() {
        super.onCreate()
        destroyed = false
        stopping = false
        // Promote to FGS during onCreate as well — BOOT_COMPLETED / package-replaced
        // paths can sit in onCreate before onStartCommand and still burn the FGS timer.
        promoteForegroundNotification()
        McpChannelGuard.onBecameIdle = {
            // Lock only the pending check; never run CIO under this lock.
            val shouldRestart = synchronized(this) {
                if (!destroyed && McpChannelGuard.pendingNetworkRestart && !McpChannelGuard.isBusy()) {
                    McpChannelGuard.pendingNetworkRestart = false
                    true
                } else {
                    false
                }
            }
            if (shouldRestart) scheduleUpMcpServer()
        }
        networkChangedListener.onNetworkChanged = {
            synchronized(this) {
                if (!destroyed && !stopping) {
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
                                scheduleUpMcpServer()
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
        // FGS timeout / ANR (Subject: executing service … waited 30003ms):
        // must startForeground before any CIO stop/start or NSD work.
        promoteForegroundNotification()
        val sticky = super.onStartCommand(intent, flags, startId)
        // BaseService returns START_NOT_STICKY after stopSelfResult when FGS start is denied —
        // do not schedule CIO/NSD in that path (same pattern as AudioCacheService).
        if (sticky == START_NOT_STICKY) return sticky
        when (intent?.action) {
            IntentAction.stop -> stopServiceWithNotification()
            "copyHostAddress" -> sendToClip(hostAddress)
            ACTION_RESTART -> {
                terminalStopJob?.cancel()
                terminalStopJob = null
                stopping = false
                scheduleUpMcpServer()
            }
            else -> {
                terminalStopJob?.cancel()
                terminalStopJob = null
                stopping = false
                scheduleUpMcpServer()
            }
        }
        return sticky
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
        terminalStopJob?.cancel()
        terminalStopJob = null
        destroyed = true
        stopping = true
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

    /** Queue engine bring-up off the main/binder thread (never block FGS start). */
    private fun scheduleUpMcpServer() {
        if (destroyed) return
        execute(context = Dispatchers.IO) {
            requestUpMcpServer()
        }
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
    private fun stopServiceWithNotification() {
        appCtx.putPrefBoolean(PreferKey.mcpService, false)
        McpWatchdog.cancel(this)
        if (stopping) return
        stopping = true
        isRun = false
        stopEngine()
        hostAddress = ""
        activeAddressKeys = emptyList()
        postEvent(EventBus.MCP_SERVICE, "")
        val (builder, promoted) = createNotification(terminal = true)
        if (!promoted) {
            stopSelf()
            return
        }
        startForeground(NotificationId.McpService, builder.build())
        terminalStopJob?.cancel()
        terminalStopJob = lifecycleScope.launch {
            delay(TERMINAL_NOTIFICATION_DURATION)
            notificationManager.cancel(NotificationId.McpService)
            stopSelf()
        }
    }

    @Synchronized
    private fun upMcpServer() {
        if (destroyed) return
        if (stopping) return
        // Re-check under the same lock: a tool may have started since idle notify.
        if (McpChannelGuard.isBusy()) {
            McpChannelGuard.pendingNetworkRestart = true
            return
        }
        val token = AppConfig.jsSourceApiToken
        if (AppConfig.jsSourceApiTokenRequired && token.isNullOrBlank()) {
            failStart(getString(R.string.mcp_service_token_required), retry = false)
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
                    tokenRequiredProvider = { AppConfig.jsSourceApiTokenRequired },
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
            startAttempt = 0
            // Keep user intent: crash/restart must not clear this.
            appCtx.putPrefBoolean(PreferKey.mcpService, true)
            activeAddressKeys = addresses.mapNotNull { it.hostAddress }.sorted()
            updateAddresses(addresses, port)
            nsdPublisher.republish(port)
            McpWatchdog.schedule(this)
            McpChannelGuard.pendingNetworkRestart = false
        } catch (error: Exception) {
            error.printOnDebug()
            failStart(
                error.localizedMessage ?: getString(R.string.mcp_service_start_failed),
                retry = true,
            )
        }
    }

    private fun stopEngine() {
        engine?.stop(500, 1_000)
        engine = null
    }

    /**
     * Keep FGS alive and retry quickly on transient bind failures.
     * Only [IntentAction.stop] clears PreferKey.mcpService.
     */
    private fun failStart(message: String, retry: Boolean) {
        isRun = false
        nsdPublisher.unpublish()
        toastOnUi(message)
        val preferOn = getPrefBoolean(PreferKey.mcpService, false)
        if (retry && preferOn && startAttempt < MAX_START_ATTEMPTS) {
            val attempt = startAttempt + 1
            startAttempt = attempt
            val delayMs = START_RETRY_DELAYS_MS[(attempt - 1).coerceAtMost(START_RETRY_DELAYS_MS.lastIndex)]
            notificationList = mutableListOf(
                getString(R.string.mcp_service_start_retry, attempt, message)
            )
            startForegroundNotification()
            execute(context = Dispatchers.IO) {
                delay(delayMs)
                if (!destroyed && getPrefBoolean(PreferKey.mcpService, false)) {
                    requestUpMcpServer()
                }
            }
            return
        }
        notificationList = mutableListOf(message)
        startForegroundNotification()
        // Do not persist mcpService=false — preference stays true for transient bind failures.
        // Config errors (retry=false, e.g. missing token) must not arm the ~3 min watchdog loop.
        if (retry) {
            McpWatchdog.schedule(this)
        } else {
            McpWatchdog.cancel(this)
        }
        stopSelf()
    }

    private fun updateAddresses(
        addresses: List<java.net.InetAddress> = NetworkUtils.getLocalIPAddress(),
        port: Int = getPort(),
    ) {
        if (stopping) return
        notificationList = McpAccess.endpointUrls(addresses, port).toMutableList()
        hostAddress = notificationList.first()
        startForegroundNotification()
        postEvent(EventBus.MCP_SERVICE, hostAddress)
    }

    private fun getPort(): Int {
        return AppConfig.mcpPort.takeIf { it in 1024..65530 } ?: 1236
    }

    override fun startForegroundNotification() {
        val (builder, _) = createNotification(terminal = stopping)
        startForeground(NotificationId.McpService, builder.build())
    }

    private fun createNotification(terminal: Boolean = false): Pair<NotificationCompat.Builder, Boolean> {
        val statusText = getString(
            if (terminal) R.string.mcp_service_live_stopped else R.string.mcp_service_live_started
        )
        val builder = NotificationCompat.Builder(this, AppConst.channelIdWeb)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSmallIcon(R.drawable.ic_web_service_noti)
            .setOngoing(!terminal)
            .setContentTitle(statusText)
            .setContentText(notificationList.joinToString("\n"))
            .setContentIntent(servicePendingIntent<McpService>("copyHostAddress"))
        val promoted = builder.applyPromotedProgress(
            this,
            AppConst.channelIdWeb,
            eligible = terminal || isRun,
            ongoing = true,
            max = 0,
            progress = 0,
            criticalText = statusText,
            terminal = terminal
        )
        if (!promoted) {
            builder.setContentTitle(getString(R.string.mcp_service))
        }
        if (!terminal) {
            builder.addAction(
                R.drawable.ic_stop_black_24dp,
                getString(R.string.cancel),
                servicePendingIntent<McpService>(IntentAction.stop),
            )
        } else if (promoted) {
            builder.setTimeoutAfter(TERMINAL_NOTIFICATION_DURATION)
        }
        return builder to promoted
    }
}
