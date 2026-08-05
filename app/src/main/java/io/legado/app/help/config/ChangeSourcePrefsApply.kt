package io.legado.app.help.config

import android.net.Uri
import io.legado.app.model.checkalgo.ChangeSourceLog

/**
 * Apply 换源 prefs from deep link / MCP / agent automation.
 *
 * Deep link: `legado://import/changeSourcePrefs?loadWordCount=true&earlyStop=true&earlyStopCount=20`
 */
object ChangeSourcePrefsApply {

    fun apply(
        loadWordCount: Boolean? = null,
        earlyStop: Boolean? = null,
        earlyStopCount: Int? = null,
    ): String {
        val applied = mutableListOf<String>()
        loadWordCount?.let {
            AppConfig.changeSourceLoadWordCount = it
            applied += "changeSourceLoadWordCount=$it"
        }
        earlyStop?.let {
            AppConfig.changeSourceEarlyStop = it
            applied += "changeSourceEarlyStop=$it"
        }
        earlyStopCount?.let {
            val n = it.coerceIn(1, 999)
            AppConfig.changeSourceEarlyStopCount = n
            applied += "changeSourceEarlyStopCount=$n"
        }
        val msg = if (applied.isEmpty()) {
            "no prefs changed"
        } else {
            applied.joinToString(", ")
        }
        ChangeSourceLog.i("prefs $msg")
        return msg
    }

    fun applyFromUri(uri: Uri): String {
        return apply(
            loadWordCount = uri.getQueryParameter("loadWordCount")?.toBooleanStrictOrNull(),
            earlyStop = uri.getQueryParameter("earlyStop")?.toBooleanStrictOrNull(),
            earlyStopCount = uri.getQueryParameter("earlyStopCount")?.toIntOrNull(),
        )
    }

    fun snapshot(): String = buildString {
        append("changeSourceLoadWordCount=").append(AppConfig.changeSourceLoadWordCount)
        append(", changeSourceEarlyStop=").append(AppConfig.changeSourceEarlyStop)
        append(", changeSourceEarlyStopCount=").append(AppConfig.changeSourceEarlyStopCount)
    }
}
