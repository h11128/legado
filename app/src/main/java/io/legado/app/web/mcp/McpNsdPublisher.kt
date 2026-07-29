package io.legado.app.web.mcp

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import io.legado.app.utils.printOnDebug
import splitties.systemservices.wifiManager

/**
 * Publish MCP on LAN via Android NSD (DNS-SD), mirroring VoiceLog's mDNS publish
 * but with the phone as the server: `_legado-mcp._tcp`.
 *
 * Republish waits for unregister to finish before registering again (avoids
 * onRegistrationFailed storms on Wi‑Fi address churn).
 */
class McpNsdPublisher(context: Context) {

    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val lock = Any()
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile
    private var publishedPort: Int? = null
    @Volatile
    private var pendingPort: Int? = null
    @Volatile
    private var unregistering = false

    fun publish(port: Int) {
        synchronized(lock) {
            if (publishedPort == port && registrationListener != null && !unregistering) return
            if (unregistering) {
                pendingPort = port
                return
            }
            if (registrationListener != null) {
                pendingPort = port
                unpublishLocked()
                return
            }
            registerLocked(port)
        }
    }

    fun republish(port: Int) {
        publish(port)
    }

    fun unpublish() {
        synchronized(lock) {
            pendingPort = null
            unpublishLocked()
        }
    }

    private fun registerLocked(port: Int) {
        acquireMulticastLock()
        val info = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = SERVICE_TYPE
            this.port = port
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                setAttribute("path", McpAccess.PATH)
                setAttribute("app", "legado")
            }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                publishedPort = port
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Exception("NSD register failed code=$errorCode").printOnDebug()
                synchronized(lock) {
                    if (registrationListener === this) {
                        registrationListener = null
                        publishedPort = null
                        releaseMulticastLock()
                        val next = pendingPort
                        pendingPort = null
                        if (next != null) registerLocked(next)
                    }
                }
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                synchronized(lock) {
                    if (registrationListener === this) {
                        registrationListener = null
                    }
                    unregistering = false
                    publishedPort = null
                    releaseMulticastLock()
                    val next = pendingPort
                    pendingPort = null
                    if (next != null) registerLocked(next)
                }
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Exception("NSD unregister failed code=$errorCode").printOnDebug()
                synchronized(lock) {
                    unregistering = false
                    if (registrationListener === this) {
                        registrationListener = null
                    }
                    publishedPort = null
                    releaseMulticastLock()
                    val next = pendingPort
                    pendingPort = null
                    if (next != null) registerLocked(next)
                }
            }
        }
        registrationListener = listener
        try {
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (error: Exception) {
            error.printOnDebug()
            registrationListener = null
            publishedPort = null
            releaseMulticastLock()
        }
    }

    private fun unpublishLocked() {
        val listener = registrationListener ?: run {
            releaseMulticastLock()
            publishedPort = null
            unregistering = false
            return
        }
        unregistering = true
        try {
            nsdManager.unregisterService(listener)
        } catch (error: Exception) {
            error.printOnDebug()
            registrationListener = null
            unregistering = false
            publishedPort = null
            releaseMulticastLock()
            val next = pendingPort
            pendingPort = null
            if (next != null) registerLocked(next)
        }
    }

    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        val lockWifi = wifiManager?.createMulticastLock(MULTICAST_TAG) ?: return
        lockWifi.setReferenceCounted(false)
        try {
            lockWifi.acquire()
            multicastLock = lockWifi
        } catch (error: Exception) {
            error.printOnDebug()
        }
    }

    private fun releaseMulticastLock() {
        val held = multicastLock ?: return
        multicastLock = null
        try {
            if (held.isHeld) held.release()
        } catch (_: Exception) {
        }
    }

    companion object {
        const val SERVICE_TYPE = "_legado-mcp._tcp."
        const val SERVICE_NAME = "legado-mcp"
        private const val MULTICAST_TAG = "legado-mcp-nsd"
    }
}
