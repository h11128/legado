package io.legado.app.model.checkalgo

/**
 * Fixed per-path ask timeouts (pre-RFC behaviour).
 *
 * Do **not** tier by [RespondTimeRank]: SUCCESS→60s doubles global search,
 * UNKNOWN→30s halves 换源 on fresh installs, and FAILURE→8s self-reinforces.
 */
object AskTimeout {

    /** Global search — original fixed budget. */
    const val SEARCH_MS = 30_000L

    /** Manual / chapter 换源 — original fixed budget wrapping the whole probe. */
    const val CHANGE_SOURCE_MS = 60_000L

    /** Multi-step auto 换源 (search+info+toc+content). */
    const val AUTO_CHANGE_MS = 180_000L
}
