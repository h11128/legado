package io.legado.app.web.mcp

import io.legado.app.model.Debug
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Cross-cutting MCP liveness / busy state for health checks and hang recovery.
 *
 * Thread 59f4efb9: phone tool calls can stop returning while [Debug] / check gates
 * stay held; clients need a cheap probe and a stale-release path.
 *
 * Check progress counters are mirrored here so [healthJson] never touches
 * [McpSourceCheckJob] (which pulls Android/Room and breaks JVM unit tests).
 */
object McpChannelGuard {

    /** If debug callback is held longer than this, treat as wedged. */
    const val STALE_DEBUG_MS = 180_000L

    /** If a check job reports no progress for this long, treat as wedged. */
    const val STALE_CHECK_MS = 180_000L

    /** Shared with [registerMcpDebugTools] so force-reset can unlock a wedged holder. */
    val debugMutex = Mutex()

    @Volatile
    private var lastToolName: String? = null

    @Volatile
    private var lastToolAtMs: Long = 0L

    @Volatile
    private var debugAcquiredAtMs: Long = 0L

    @Volatile
    private var checkRunning: Boolean = false

    @Volatile
    private var checkFinished: Int = 0

    @Volatile
    private var checkTotal: Int = 0

    @Volatile
    private var checkLastProgressAtMs: Long = 0L

    @Volatile
    var pendingNetworkRestart: Boolean = false

    @Volatile
    var onBecameIdle: (() -> Unit)? = null

    fun noteTool(name: String) {
        lastToolName = name
        lastToolAtMs = System.currentTimeMillis()
    }

    fun noteDebugAcquired() {
        debugAcquiredAtMs = System.currentTimeMillis()
    }

    fun noteDebugReleased() {
        debugAcquiredAtMs = 0L
        maybeNotifyIdle()
    }

    fun noteCheckStarted(total: Int) {
        checkRunning = true
        checkTotal = total
        checkFinished = 0
        checkLastProgressAtMs = System.currentTimeMillis()
    }

    fun noteCheckProgress(finished: Int = checkFinished, total: Int = checkTotal) {
        checkFinished = finished
        checkTotal = total
        checkLastProgressAtMs = System.currentTimeMillis()
    }

    fun noteCheckFinished(finished: Int = checkFinished, total: Int = checkTotal) {
        checkRunning = false
        checkFinished = finished
        checkTotal = total
        checkLastProgressAtMs = System.currentTimeMillis()
        maybeNotifyIdle()
    }

    fun isBusy(): Boolean {
        return Debug.callback != null || Debug.isChecking || checkRunning
    }

    fun isCheckRunning(): Boolean = checkRunning

    fun checkLastProgressAtMs(): Long = checkLastProgressAtMs

    fun forceUnlockDebugMutex() {
        if (debugMutex.isLocked) {
            runCatching { debugMutex.unlock() }
        }
    }

    /**
     * Force-clear wedged debug/check gates. Returns a short human summary.
     */
    fun forceReleaseStale(nowMs: Long = System.currentTimeMillis()): String {
        val parts = mutableListOf<String>()
        val debugHeld = debugAcquiredAtMs
        if (Debug.callback != null && debugHeld > 0L && nowMs - debugHeld >= STALE_DEBUG_MS) {
            Debug.forceCancelDebug()
            debugAcquiredAtMs = 0L
            forceUnlockDebugMutex()
            parts += "stale_debug"
        }
        if (checkRunning && checkLastProgressAtMs > 0L &&
            nowMs - checkLastProgressAtMs >= STALE_CHECK_MS
        ) {
            // Cancel only — Job finally owns finishChecking / noteCheckFinished / idle.
            McpSourceCheckJob.requestStopFromWatchdog()
            parts += "stale_check"
        }
        // Always attempt idle notify (e.g. pending network restart after debug clear).
        maybeNotifyIdle()
        return if (parts.isEmpty()) "ok" else parts.joinToString(",")
    }

    /** Emergency unlock for MCP tool `reset_mcp_channel`. */
    fun forceResetAll(): String {
        Debug.forceCancelDebug()
        debugAcquiredAtMs = 0L
        forceUnlockDebugMutex()
        val checkMsg = McpSourceCheckJob.requestStopFromWatchdog()
        // Keep pendingNetworkRestart; idle callback applies it when check finally ends.
        maybeNotifyIdle()
        return "debug cleared; $checkMsg"
    }

    fun healthJson(serviceRun: Boolean): String {
        return try {
            val now = System.currentTimeMillis()
            val debugBusy = Debug.callback != null
            val debugHeldMs = debugAcquiredAtMs.takeIf { it > 0L }?.let { now - it }
            val checkStallMs = checkLastProgressAtMs.takeIf { it > 0L }?.let { now - it }
            val stale = (debugBusy && (debugHeldMs ?: 0L) >= STALE_DEBUG_MS) ||
                (checkRunning && (checkStallMs ?: 0L) >= STALE_CHECK_MS)
            val busy = isBusy()
            val (hostThrottled, hostEwmaLow) = McpSourceCheckJob.antiBlockSnapshot()
            buildJsonObject {
                put("ok", serviceRun && !stale)
                put("serviceRun", serviceRun)
                put("busy", busy)
                put("debugBusy", debugBusy)
                put("checkRunning", checkRunning)
                put("checkFinished", checkFinished)
                put("checkTotal", checkTotal)
                if (lastToolName != null) put("lastTool", lastToolName!!) else put("lastTool", JsonNull)
                if (lastToolAtMs > 0L) put("lastToolAt", lastToolAtMs) else put("lastToolAt", JsonNull)
                if (debugHeldMs != null) put("debugHeldMs", debugHeldMs) else put("debugHeldMs", JsonNull)
                if (checkStallMs != null) put("checkStallMs", checkStallMs) else put("checkStallMs", JsonNull)
                put("pendingNetworkRestart", pendingNetworkRestart)
                put("stale", stale)
                if (hostThrottled != null) {
                    put(
                        "hostThrottled",
                        buildJsonObject {
                            hostThrottled.forEach { (host, tokens) -> put(host, tokens) }
                        },
                    )
                } else {
                    put("hostThrottled", JsonNull)
                }
                if (hostEwmaLow != null) {
                    put(
                        "hostEwmaLow",
                        buildJsonObject {
                            hostEwmaLow.forEach { (host, rate) -> put(host, rate) }
                        },
                    )
                } else {
                    put("hostEwmaLow", JsonNull)
                }
            }.toString()
        } catch (t: Throwable) {
            buildJsonObject {
                put("ok", false)
                put("serviceRun", serviceRun)
                put("error", t.message ?: t.toString())
            }.toString()
        }
    }

    fun maybeNotifyIdle() {
        if (!isBusy()) {
            onBecameIdle?.invoke()
        }
    }
}
