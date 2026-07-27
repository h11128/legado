package io.legado.app.model

import io.legado.app.help.config.AppConfig
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Short-TTL negative cache + in-memory positive DNS warm for bulk checks.
 * Does not mutate read-only [AppConfig.addressCache].
 */
object CheckDnsGuard {

    private const val FAIL_TTL_MS = 5 * 60_000L
    private const val OK_TTL_MS = 30 * 60_000L

    private data class FailEntry(val until: Long)
    private data class OkEntry(val addresses: List<InetAddress>, val until: Long)

    private val fails = ConcurrentHashMap<String, FailEntry>()
    private val oks = ConcurrentHashMap<String, OkEntry>()

    fun isBlocked(host: String): Boolean {
        val entry = fails[host] ?: return false
        if (entry.until <= System.currentTimeMillis()) {
            fails.remove(host, entry)
            return false
        }
        return true
    }

    fun lookupCached(host: String): List<InetAddress>? {
        AppConfig.addressCache[host]?.takeIf { it.isNotEmpty() }?.let { return it }
        val entry = oks[host] ?: return null
        if (entry.until <= System.currentTimeMillis()) {
            oks.remove(host, entry)
            return null
        }
        return entry.addresses
    }

    fun markFailed(host: String) {
        fails[host] = FailEntry(System.currentTimeMillis() + FAIL_TTL_MS)
        oks.remove(host)
    }

    fun markOk(host: String, addresses: List<InetAddress>? = null) {
        fails.remove(host)
        if (!addresses.isNullOrEmpty()) {
            oks[host] = OkEntry(addresses, System.currentTimeMillis() + OK_TTL_MS)
        }
    }

    fun clear() {
        fails.clear()
        oks.clear()
    }
}
