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
        filterNonNovelHost: Boolean? = null,
        filterNonBookIntro: Boolean? = null,
        dropContentBad: Boolean? = null,
        checkAuthor: Boolean? = null,
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
        filterNonNovelHost?.let {
            AppConfig.changeSourceFilterNonNovelHost = it
            applied += "changeSourceFilterNonNovelHost=$it"
        }
        filterNonBookIntro?.let {
            AppConfig.changeSourceFilterNonBookIntro = it
            applied += "changeSourceFilterNonBookIntro=$it"
        }
        dropContentBad?.let {
            AppConfig.changeSourceDropContentBad = it
            applied += "changeSourceDropContentBad=$it"
        }
        checkAuthor?.let {
            AppConfig.changeSourceCheckAuthor = it
            applied += "changeSourceCheckAuthor=$it"
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
            filterNonNovelHost = uri.getQueryParameter("filterNonNovelHost")
                ?.toBooleanStrictOrNull(),
            filterNonBookIntro = uri.getQueryParameter("filterNonBookIntro")
                ?.toBooleanStrictOrNull(),
            dropContentBad = uri.getQueryParameter("dropContentBad")?.toBooleanStrictOrNull(),
            checkAuthor = uri.getQueryParameter("checkAuthor")?.toBooleanStrictOrNull(),
        )
    }

    fun snapshot(): String = buildString {
        append("changeSourceLoadWordCount=").append(AppConfig.changeSourceLoadWordCount)
        append(", changeSourceEarlyStop=").append(AppConfig.changeSourceEarlyStop)
        append(", changeSourceEarlyStopCount=").append(AppConfig.changeSourceEarlyStopCount)
        append(", changeSourceCheckAuthor=").append(AppConfig.changeSourceCheckAuthor)
        append(", changeSourceFilterNonNovelHost=")
            .append(AppConfig.changeSourceFilterNonNovelHost)
        append(", changeSourceFilterNonBookIntro=")
            .append(AppConfig.changeSourceFilterNonBookIntro)
        append(", changeSourceDropContentBad=").append(AppConfig.changeSourceDropContentBad)
    }
}
