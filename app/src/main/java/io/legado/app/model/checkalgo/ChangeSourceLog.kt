package io.legado.app.model.checkalgo

import android.util.Log
import io.legado.app.constant.AppLog

/**
 * Always-on logcat tag for 换源 smoke / agent filters (`adb logcat -s LegadoChangeSource`).
 *
 * High-frequency lines (hit/miss/phase/list+) stay **logcat-only** so the ring buffer
 * keeps `start`/`finish`. Milestones also mirror into [AppLog] for the in-app page.
 *
 * Keywords: `start` / `hit` / `deep` / `phase` / `list+` / `progress` / `early-stop` /
 * `miss` / `finish` / `verify-start` / `verify-finish` / `stop`.
 */
object ChangeSourceLog {

    const val TAG = "LegadoChangeSource"

    private val appLogPrefixes = setOf(
        "start",
        "finish",
        "progress",
        "early-stop",
        "stop",
        "verify-start",
        "verify-finish",
    )

    fun i(message: String) {
        Log.i(TAG, message)
        if (shouldMirrorAppLog(message)) {
            AppLog.put("换源 $message")
        }
    }

    fun w(message: String, throwable: Throwable? = null) {
        Log.w(TAG, message, throwable)
        AppLog.put("换源 $message", throwable)
    }

    private fun shouldMirrorAppLog(message: String): Boolean {
        val key = message.substringBefore(' ')
        return key in appLogPrefixes
    }
}
