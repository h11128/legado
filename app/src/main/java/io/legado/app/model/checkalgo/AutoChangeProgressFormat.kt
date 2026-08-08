package io.legado.app.model.checkalgo

import android.content.Context
import android.widget.TextView
import io.legado.app.R
import io.legado.app.ui.widget.anima.RefreshProgressBar

/** Line 1: auto-换源 counters. */
fun Context.formatAutoChangeProgressMetrics(progress: AutoChangeProgressUi): String {
    return getString(
        R.string.source_auto_change_metrics,
        progress.completed,
        progress.total.coerceAtLeast(1),
        progress.inFlight,
        progress.concurrency.coerceAtLeast(1),
    )
}

/** Line 2: probing label or done/idle. */
fun Context.formatAutoChangeProgressCurrent(progress: AutoChangeProgressUi): String {
    val label = progress.label.trim()
    return when {
        label.isNotEmpty() -> label
        progress.finished -> getString(R.string.change_source_progress_done)
        else -> getString(R.string.source_auto_changing)
    }
}

fun bindAutoChangeProgressStrip(
    metricsView: TextView,
    currentView: TextView,
    progress: AutoChangeProgressUi,
) {
    val ctx = metricsView.context
    metricsView.text = ctx.formatAutoChangeProgressMetrics(progress)
    currentView.text = ctx.formatAutoChangeProgressCurrent(progress)
    currentView.isSelected = true
}

/**
 * Determinate LTR bar — same rule as manual 换源 / 搜书.
 * Never toggle [RefreshProgressBar.isAutoLoading] (forces maxProgress=0).
 */
fun RefreshProgressBar.bindAutoChangeProgress(completed: Int, total: Int) {
    val max = total.coerceAtLeast(1)
    maxProgress = max
    setDurProgress(completed.coerceIn(0, max))
}
