package io.legado.app.model.checkalgo

/**
 * Live progress for reading-page auto-换源 overlay (bar + metrics strip).
 *
 * Mirrors the manual 换源 dialog strip shape, without hit/quality counters
 * (auto path takes the first success and has no result list).
 */
data class AutoChangeProgressUi(
    val completed: Int = 0,
    val total: Int = 0,
    val inFlight: Int = 0,
    val concurrency: Int = 0,
    /** Second line: e.g. "询问中 源A、源B". */
    val label: String = "",
    val finished: Boolean = false,
)
