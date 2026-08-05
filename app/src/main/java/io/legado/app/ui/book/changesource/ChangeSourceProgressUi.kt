package io.legado.app.ui.book.changesource

/**
 * Progress payload for 整书/单章换源 toolbar subtitle.
 *
 * [completed] = ask-phase finished (search returned), NOT deep word-count done.
 * [inFlight] = ask currently running. [deepInFlight] = toc/content probes still running.
 */
data class ChangeSourceProgressUi(
    val completed: Int = 0,
    /** Sources currently in the ask (search) wave. */
    val inFlight: Int = 0,
    /** Configured ask mapParallel concurrency (AppConfig.threadCount). */
    val concurrency: Int = 0,
    /** Hits still loading toc/content after ask released the slot. */
    val deepInFlight: Int = 0,
    val label: String = "",
    val qualityOk: Int = 0,
    val earlyStopped: Boolean = false,
    val finished: Boolean = false,
)
