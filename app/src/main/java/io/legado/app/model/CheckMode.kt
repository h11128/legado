package io.legado.app.model

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Marks an in-flight book-source check so hot paths can take lighter branches
 * (TOC sampling, body caps, nested concurrency, WebView delay).
 */
data class CheckMode(
    val tocSampleChapters: Int = 2,
    val tocMaxPages: Int = 1,
    val nestedMapAsync: Int = 2,
    val maxBodyBytes: Int = 1_500_000,
    val skipDiscoveryIfSearchOk: Boolean = true,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<CheckMode> {
        val Default = CheckMode()

        suspend fun current(): CheckMode? = coroutineContext[Key]

        suspend fun isActive(): Boolean = current() != null

        suspend fun nestedConcurrency(fallback: Int): Int {
            val mode = current() ?: return fallback
            return mode.nestedMapAsync.coerceIn(1, fallback.coerceAtLeast(1))
        }
    }
}
