package io.legado.app.model.checkalgo

/**
 * Adaptive ask timeout for 整书换源 (RFC-002).
 *
 * Full budget for SUCCESS/UNKNOWN; grace budget for FAILURE or session-demoted.
 * Never use the rejected 60/30/8 tiering (see [AskTimeout] KDoc).
 */
object AskTimeoutBudget {

    const val BASE_MS = AskTimeout.CHANGE_SOURCE_MS
    const val GRACE_MS = 20_000L
    const val MIN_MS = 12_000L

    /**
     * @param respondTime persisted [io.legado.app.data.entities.BookSource.respondTime]
     * @param sessionDemoted true when [ChangeSourceAskMemory.isDemoted]
     */
    fun forChangeSourceAsk(respondTime: Long, sessionDemoted: Boolean): Long {
        val rank = RespondTimeRank.classify(respondTime)
        val raw = when {
            sessionDemoted -> GRACE_MS
            rank == RespondTimeRank.FAILURE -> GRACE_MS
            else -> BASE_MS
        }
        return raw.coerceIn(MIN_MS, BASE_MS)
    }

    fun rankName(respondTime: Long): String = when (RespondTimeRank.classify(respondTime)) {
        RespondTimeRank.SUCCESS -> "SUCCESS"
        RespondTimeRank.UNKNOWN -> "UNKNOWN"
        else -> "FAILURE"
    }
}
