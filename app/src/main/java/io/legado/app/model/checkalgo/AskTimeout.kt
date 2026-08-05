package io.legado.app.model.checkalgo

/**
 * Fixed per-path ask timeouts (pre-RFC behaviour) for search / auto 换源.
 *
 * Manual 换源 ask uses [AskTimeoutBudget] (RFC-002) instead of always [CHANGE_SOURCE_MS].
 *
 * Do **not** reintroduce SUCCESS→60 / UNKNOWN→30 / FAILURE→8 tiering:
 * SUCCESS→60 doubles global search, UNKNOWN→30 halves 换源 on fresh installs,
 * FAILURE→8 self-reinforces.
 */
object AskTimeout {

    /** Global search — original fixed budget. */
    const val SEARCH_MS = 30_000L

    /** Manual / chapter 换源 base budget (full). Grace path: [AskTimeoutBudget.GRACE_MS]. */
    const val CHANGE_SOURCE_MS = 60_000L

    /** Multi-step auto 换源 (search+info+toc+content). */
    const val AUTO_CHANGE_MS = 180_000L
}
