package io.legado.app.model.checkalgo

import io.legado.app.data.entities.BookSource
import kotlin.math.max
import kotlin.math.min

/**
 * Shared respondTime classification and encoding (§6.1 RFC-001).
 *
 * Invariant: success &lt; [BookSource.DEFAULT_RESPOND_TIME] &lt; failure
 * for any effectiveTimeout and spending (including spending == 0).
 */
object RespondTimeRank {

    const val SUCCESS = 0
    const val UNKNOWN = 1
    const val FAILURE = 2

    fun classify(respondTime: Long): Int = when {
        respondTime < BookSource.DEFAULT_RESPOND_TIME -> SUCCESS
        respondTime == BookSource.DEFAULT_RESPOND_TIME -> UNKNOWN
        else -> FAILURE
    }

    fun encodeSuccess(elapsedMs: Long): Long {
        val elapsed = elapsedMs.coerceAtLeast(0L)
        return min(elapsed, BookSource.DEFAULT_RESPOND_TIME - 1)
    }

    fun encodeFailure(effectiveTimeoutMs: Long, spendingMs: Long): Long {
        val spending = spendingMs.coerceAtLeast(0L)
        return max(effectiveTimeoutMs, BookSource.DEFAULT_RESPOND_TIME) + spending + 1
    }

    fun encode(success: Boolean, elapsedMs: Long, effectiveTimeoutMs: Long): Long {
        return if (success) {
            encodeSuccess(elapsedMs)
        } else {
            encodeFailure(effectiveTimeoutMs, elapsedMs)
        }
    }
}
