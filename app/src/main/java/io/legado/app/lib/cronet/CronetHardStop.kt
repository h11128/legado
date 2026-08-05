package io.legado.app.lib.cronet

import java.io.IOException
import java.util.concurrent.TimeoutException

/**
 * Shared Cronet→OkHttp fallback gate: timeout/cancel must not stack a second ~60s OkHttp trip.
 */
internal object CronetHardStop {

    fun isHardStop(error: Throwable): Boolean {
        var cur: Throwable? = error
        while (cur != null) {
            when (cur) {
                is TimeoutException,
                is kotlinx.coroutines.TimeoutCancellationException -> return true
            }
            val msg = cur.message.orEmpty()
            if (msg.contains("Cronet timeout", true) ||
                msg.contains("Cronet interrupted", true) ||
                msg.contains("Cronet Request Canceled", true) ||
                msg.contains("ERR_") && msg.contains("TIMED_OUT", true) ||
                msg.equals("Canceled", true) ||
                msg.contains("net::ERR_CONNECTION_TIMED_OUT", true)
            ) {
                return true
            }
            cur = cur.cause
        }
        return false
    }

    fun asIOException(error: Exception): IOException {
        return error as? IOException ?: IOException(error.message, error)
    }
}
