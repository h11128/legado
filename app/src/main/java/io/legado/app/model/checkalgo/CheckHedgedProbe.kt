package io.legado.app.model.checkalgo

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select

/**
 * Hedged / speculative L1: start fallback if primary is slow;
 * if the first completed path fails, wait for the other path.
 */
object CheckHedgedProbe {

    suspend fun <T> hedged(
        primaryDelayMs: Long = 400,
        primary: suspend () -> T,
        fallback: suspend () -> T,
    ): T = coroutineScope {
        val primaryDeferred = async { runCatching { primary() } }
        val fallbackDeferred = async {
            delay(primaryDelayMs)
            runCatching { fallback() }
        }
        val (firstResult, fromPrimary) = select {
            primaryDeferred.onAwait { it to true }
            fallbackDeferred.onAwait { it to false }
        }
        if (firstResult.isSuccess) {
            if (fromPrimary) fallbackDeferred.cancel() else primaryDeferred.cancel()
            return@coroutineScope firstResult.getOrThrow()
        }
        val other = if (fromPrimary) fallbackDeferred else primaryDeferred
        val otherResult = other.await()
        if (otherResult.isSuccess) {
            return@coroutineScope otherResult.getOrThrow()
        }
        throw otherResult.exceptionOrNull()
            ?: firstResult.exceptionOrNull()
            ?: IllegalStateException("hedged probe failed")
    }
}
