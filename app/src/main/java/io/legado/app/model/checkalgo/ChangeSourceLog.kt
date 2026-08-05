package io.legado.app.model.checkalgo

import android.util.Log
import io.legado.app.constant.AppLog

/**
 * Always-on logcat tag for 换源 smoke / agent filters (`adb logcat -s LegadoChangeSource`).
 * Also mirrors into [AppLog] for the in-app log page.
 *
 * Keep messages single-line and keyword-stable for rg:
 * `start` / `hit` / `deep` / `phase` / `list+` / `progress` / `early-stop` / `miss` / `finish` /
 * `verify-start` / `verify-finish`.
 */
object ChangeSourceLog {

    const val TAG = "LegadoChangeSource"

    fun i(message: String) {
        Log.i(TAG, message)
        AppLog.put("换源 $message")
    }

    fun w(message: String, throwable: Throwable? = null) {
        Log.w(TAG, message, throwable)
        AppLog.put("换源 $message", throwable)
    }
}
