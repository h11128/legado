package io.legado.app.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.constant.PreferKey
import io.legado.app.receiver.NetworkChangedListener
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.postEvent
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.sendToClip
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.startForegroundServiceCompat
import io.legado.app.utils.startService
import io.legado.app.utils.stopService
import io.legado.app.utils.toastOnUi
import io.legado.app.web.HttpServer
import io.legado.app.web.WebSocketServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import splitties.init.appCtx
import splitties.systemservices.powerManager
import splitties.systemservices.wifiManager
import java.io.IOException

class WebService : BaseService() {

    companion object {
        @Volatile
        var isRun = false

        @Volatile
        var hostAddress = ""

        private const val MAX_START_ATTEMPTS = 5
        private val START_RETRY_DELAYS_MS = longArrayOf(2_000, 5_000, 10_000, 20_000, 30_000)

        fun start(context: Context) {
            context.startService<WebService>()
        }

        fun startForeground(context: Context) {
            val intent = Intent(context, WebService::class.java)
            context.startForegroundServiceCompat(intent)
        }

        fun stop(context: Context) {
            context.stopService<WebService>()
        }

        fun serve() {
            appCtx.startService<WebService> {
                action = "serve"
            }
        }
    }

    private val useWakeLock = appCtx.getPrefBoolean(PreferKey.webServiceWakeLock, false)
    private val wakeLock: PowerManager.WakeLock by lazy {
        powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "legado:WebService")
            .apply {
                setReferenceCounted(false)
            }
    }
    private val wifiLock by lazy {
        @Suppress("DEPRECATION")
        wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "legado:WebService")
            ?.apply {
                setReferenceCounted(false)
            }
    }
    private var httpServer: HttpServer? = null
    private var webSocketServer: WebSocketServer? = null
    private var notificationList = mutableListOf(appCtx.getString(R.string.service_starting))
    @Volatile
    private var destroyed = false
    private var startAttempt = 0
    private val networkChangedListener by lazy {
        NetworkChangedListener(this)
    }

    @SuppressLint("WakelockTimeout")
    override fun onCreate() {
        super.onCreate()
        destroyed = false
        promoteForegroundNotification()
        if (useWakeLock) {
            wakeLock.acquire()
            wifiLock?.acquire()
        }
        upTile(true)
        networkChangedListener.register()
        networkChangedListener.onNetworkChanged = {
            if (!destroyed) {
                refreshAddressNotification()
            }
        }
    }

    @SuppressLint("WakelockTimeout")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Same FGS rule as McpService: never bind ports before startForeground.
        promoteForegroundNotification()
        val sticky = super.onStartCommand(intent, flags, startId)
        if (sticky == START_NOT_STICKY) return sticky
        when (intent?.action) {
            IntentAction.stop -> stopSelf()
            "copyHostAddress" -> sendToClip(hostAddress)
            "serve" -> {
                if (useWakeLock) {
                    wakeLock.acquire()
                    wifiLock?.acquire()
                }
                scheduleUpWebServer()
            }
            else -> scheduleUpWebServer()
        }
        return sticky
    }

    @Synchronized
    override fun onDestroy() {
        destroyed = true
        if (useWakeLock) {
            wakeLock.release()
            wifiLock?.release()
        }
        networkChangedListener.unRegister()
        isRun = false
        stopServers()
        postEvent(EventBus.WEB_SERVICE, "")
        upTile(false)
        super.onDestroy()
    }

    private fun scheduleUpWebServer() {
        if (destroyed) return
        execute(context = Dispatchers.IO) {
            upWebServer()
        }
    }

    @Synchronized
    private fun upWebServer() {
        if (destroyed) return
        stopServers()
        val addressList = NetworkUtils.getLocalIPAddress()
        if (addressList.isEmpty()) {
            failStart("web service cant start, no ip address", retry = true)
            return
        }
        val port = getPort()
        httpServer = HttpServer(port)
        webSocketServer = WebSocketServer(port + 1)
        try {
            httpServer?.start()
            webSocketServer?.start(1000 * 30) // 通信超时设置
            notificationList.clear()
            notificationList.addAll(addressList.map { address ->
                getString(
                    R.string.http_ip,
                    address.hostAddress,
                    port
                )
            })
            hostAddress = notificationList.first()
            isRun = true
            startAttempt = 0
            postEvent(EventBus.WEB_SERVICE, hostAddress)
            startForegroundNotification()
        } catch (e: IOException) {
            e.printOnDebug()
            failStart(e.localizedMessage ?: "web service start failed", retry = true)
        }
    }

    private fun stopServers() {
        if (httpServer?.isAlive == true) {
            httpServer?.stop()
        }
        if (webSocketServer?.isAlive == true) {
            webSocketServer?.stop()
        }
        httpServer = null
        webSocketServer = null
    }

    private fun failStart(message: String, retry: Boolean) {
        isRun = false
        stopServers()
        toastOnUi(message)
        if (retry && !destroyed && startAttempt < MAX_START_ATTEMPTS) {
            val attempt = startAttempt + 1
            startAttempt = attempt
            val delayMs = START_RETRY_DELAYS_MS[(attempt - 1).coerceAtMost(START_RETRY_DELAYS_MS.lastIndex)]
            notificationList = mutableListOf(
                getString(R.string.web_service_start_retry, attempt, message)
            )
            startForegroundNotification()
            execute(context = Dispatchers.IO) {
                delay(delayMs)
                if (!destroyed) {
                    upWebServer()
                }
            }
            return
        }
        notificationList = mutableListOf(message)
        startForegroundNotification()
        stopSelf()
    }

    private fun refreshAddressNotification() {
        val addressList = NetworkUtils.getLocalIPAddress()
        notificationList.clear()
        if (addressList.any()) {
            notificationList.addAll(addressList.map { address ->
                getString(
                    R.string.http_ip,
                    address.hostAddress,
                    getPort()
                )
            })
            hostAddress = notificationList.first()
        } else {
            hostAddress = getString(R.string.network_connection_unavailable)
            notificationList.add(hostAddress)
        }
        startForegroundNotification()
        postEvent(EventBus.WEB_SERVICE, hostAddress)
    }

    private fun getPort(): Int {
        var port = getPrefInt(PreferKey.webPort, 1122)
        if (port !in 1024..65530) {
            port = 1122
        }
        return port
    }

    /**
     * 更新通知
     */
    override fun startForegroundNotification() {
        val builder = NotificationCompat.Builder(this, AppConst.channelIdWeb)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSmallIcon(R.drawable.ic_web_service_noti)
            .setOngoing(true)
            .setContentTitle(getString(R.string.web_service))
            .setContentText(notificationList.joinToString("\n"))
            .setContentIntent(
                servicePendingIntent<WebService>("copyHostAddress")
            )
        builder.addAction(
            R.drawable.ic_stop_black_24dp,
            getString(R.string.cancel),
            servicePendingIntent<WebService>(IntentAction.stop)
        )
        val notification = builder.build()
        startForeground(NotificationId.WebService, notification)
    }

    @SuppressLint("ObsoleteSdkInt")
    private fun upTile(active: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            kotlin.runCatching {
                startService<WebTileService> {
                    action = if (active) {
                        IntentAction.start
                    } else {
                        IntentAction.stop
                    }
                }
            }

        }
    }
}
