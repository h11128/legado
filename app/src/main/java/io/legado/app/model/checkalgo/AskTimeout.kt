package io.legado.app.model.checkalgo

/**
 * Per-source ask timeout by historical [RespondTimeRank] class.
 */
object AskTimeout {

    const val SUCCESS_MS = 60_000L
    const val UNKNOWN_MS = 30_000L
    const val FAILURE_MS = 8_000L

    fun timeoutMs(respondTime: Long): Long {
        return when (RespondTimeRank.classify(respondTime)) {
            RespondTimeRank.SUCCESS -> SUCCESS_MS
            RespondTimeRank.FAILURE -> FAILURE_MS
            else -> UNKNOWN_MS
        }
    }

    /** Multi-step auto 换源 (search+info+toc+content) needs a wider budget. */
    fun autoChangeTimeoutMs(respondTime: Long): Long {
        return maxOf(timeoutMs(respondTime) * 3, UNKNOWN_MS)
    }
}
