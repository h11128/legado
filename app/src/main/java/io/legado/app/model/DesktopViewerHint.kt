package io.legado.app.model

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Per-check coroutine-local flag: last HTTP hop looked like a desktop ComicView / data: viewer.
 * Must not use ThreadLocal — MCP check runs on a shared Dispatchers.IO pool.
 */
class DesktopViewerHint(
    @Volatile var hit: Boolean = false,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<DesktopViewerHint>
}
