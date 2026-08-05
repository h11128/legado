package io.legado.app.ui.book.changesource

import android.content.Context
import io.legado.app.R

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
            progress.label,
        )
        else -> getString(
            R.string.change_source_progress,
            resultCount,
            progress.completed,
            total,
            progress.label,
        )
    }
}
