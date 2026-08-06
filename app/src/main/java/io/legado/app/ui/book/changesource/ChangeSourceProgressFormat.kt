package io.legado.app.ui.book.changesource

import android.content.Context
import android.widget.TextView
import io.legado.app.R
import io.legado.app.ui.widget.anima.RefreshProgressBar

/** Line 1: compact counters (no source names). */
internal fun Context.formatChangeSourceProgressMetrics(
    resultCount: Int,
    progress: ChangeSourceProgressUi,
    total: Int,
): String {
    return when {
        progress.finished && progress.earlyStopped -> getString(
            R.string.change_source_progress_metrics_early_done,
            resultCount,
            progress.hitCount,
            progress.qualityOk,
            progress.completed,
            total,
        )
        progress.earlyStopped -> getString(
            R.string.change_source_progress_metrics_early_running,
            resultCount,
            progress.hitCount,
            progress.qualityOk,
            progress.completed,
            total,
        )
        else -> getString(
            R.string.change_source_progress_metrics,
            resultCount,
            progress.hitCount,
            progress.completed,
            total,
            progress.inFlight,
            progress.concurrency.coerceAtLeast(1),
        )
    }
}

/** Line 2: current ask / deep label, or idle / done. */
internal fun Context.formatChangeSourceProgressCurrent(
    progress: ChangeSourceProgressUi,
): String {
    val label = progress.label.trim()
    return when {
        label.isNotEmpty() -> label
        progress.finished -> getString(R.string.change_source_progress_done)
        progress.earlyStopped -> getString(R.string.change_source_progress_stopping)
        else -> getString(R.string.change_source_progress_idle)
    }
}

internal fun bindChangeSourceProgressStrip(
    metricsView: TextView,
    currentView: TextView,
    resultCount: Int,
    progress: ChangeSourceProgressUi,
    total: Int,
) {
    val ctx = metricsView.context
    metricsView.text = ctx.formatChangeSourceProgressMetrics(resultCount, progress, total)
    currentView.text = ctx.formatChangeSourceProgressCurrent(progress)
    // Marquee needs selection / focus.
    currentView.isSelected = true
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
