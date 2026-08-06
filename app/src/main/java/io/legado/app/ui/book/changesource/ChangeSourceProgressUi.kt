package io.legado.app.ui.book.changesource

/**
 * Progress payload for 整书/单章换源 status strip.
 *
 * [completed] = ask-phase finished (search returned), NOT deep word-count done.
 * [inFlight] = ask currently running. [deepInFlight] = toc/content probes still running.
 * [hitCount] = title-matched sources that passed search-quality (≠ list size after content drop).
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
    /** Search hits that passed quality gate (book count, not unique origins). */
    val hitCount: Int = 0,
    val earlyStopped: Boolean = false,
    val finished: Boolean = false,
)
