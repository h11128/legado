package io.legado.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.legado.app.help.config.ChangeSourcePrefsApply
import io.legado.app.model.checkalgo.ChangeSourceLog

/**
 * Agent / smoke automation for 换源 prefs without UI.
 *
 * ```
 * adb shell am broadcast -a io.legado.app.action.SET_CHANGE_SOURCE_PREFS \
 *   --ez loadWordCount true --ez earlyStop true \
 *   -n com.legado.app.debug/io.legado.app.receiver.ChangeSourcePrefsReceiver
 * ```
 */
class ChangeSourcePrefsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        intent ?: return
        if (intent.action != ACTION) return
        val loadWordCount = if (intent.hasExtra(EXTRA_LOAD_WORD_COUNT)) {
            intent.getBooleanExtra(EXTRA_LOAD_WORD_COUNT, true)
        } else null
        val earlyStop = if (intent.hasExtra(EXTRA_EARLY_STOP)) {
            intent.getBooleanExtra(EXTRA_EARLY_STOP, true)
        } else null
        val earlyStopCount = if (intent.hasExtra(EXTRA_EARLY_STOP_COUNT)) {
            intent.getIntExtra(EXTRA_EARLY_STOP_COUNT, 20)
        } else null
        val msg = ChangeSourcePrefsApply.apply(
            loadWordCount = loadWordCount,
            earlyStop = earlyStop,
            earlyStopCount = earlyStopCount,
        )
        ChangeSourceLog.i("broadcast $msg")
    }

    companion object {
        const val ACTION = "io.legado.app.action.SET_CHANGE_SOURCE_PREFS"
        const val EXTRA_LOAD_WORD_COUNT = "loadWordCount"
        const val EXTRA_EARLY_STOP = "earlyStop"
        const val EXTRA_EARLY_STOP_COUNT = "earlyStopCount"
    }
}
