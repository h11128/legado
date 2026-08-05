package io.legado.app.ui.book.changesource

/**
 * Progress payload for 整书/单章换源 toolbar subtitle.
 */
data class ChangeSourceProgressUi(
    val completed: Int = 0,
    val label: String = "",
    val qualityOk: Int = 0,
    val earlyStopped: Boolean = false,
    val finished: Boolean = false,
)
