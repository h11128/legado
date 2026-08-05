package io.legado.app.ui.book.changesource

import android.content.Context
import io.legado.app.R
import io.legado.app.ui.widget.anima.RefreshProgressBar

internal fun Context.formatChangeSourceProgress(
    resultCount: Int,
    progress: ChangeSourceProgressUi,
    total: Int,
): String {
    return when {
        progress.finished && progress.earlyStopped -> getString(
            R.string.change_source_early_stop_done,
            resultCount,
            progress.qualityOk,
            progress.completed,
            total,
        )
        progress.earlyStopped -> getString(
            R.string.change_source_early_stop_running,
            resultCount,
            progress.qualityOk,
            progress.completed,
            total,
            progress.label.ifBlank { "…" },
        )
        else -> getString(
            R.string.change_source_progress,
            resultCount,
            progress.completed,
            total,
            progress.inFlight,
            progress.concurrency.coerceAtLeast(1),
            progress.label.ifBlank { "…" },
        )
    }
}

/**
 * Match 搜书: left-to-right determinate bar (not bouncing [RefreshProgressBar.isAutoLoading]).
 * Never toggle isAutoLoading here — its setter forces maxProgress=0.
 */
internal fun RefreshProgressBar.bindChangeSourceProgress(completed: Int, total: Int) {
    val max = total.coerceAtLeast(1)
    maxProgress = max
    setDurProgress(completed.coerceIn(0, max))
}
