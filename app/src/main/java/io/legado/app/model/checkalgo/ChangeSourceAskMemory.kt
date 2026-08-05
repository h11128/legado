package io.legado.app.model.checkalgo

import java.util.concurrent.ConcurrentHashMap

/**
 * Process-lifetime ask demotion for 换源 / search / auto 换源.
 *
 * Empty / timeout / content-bad probes must **not** write [io.legado.app.data.entities.BookSource.respondTime]
 * (RFC-001 §6.4). This set only moves URLs to the end of [AskSourceOrder] for later asks in this process.
 */
object ChangeSourceAskMemory {

    private val demotedUrls = ConcurrentHashMap.newKeySet<String>()

    fun noteMiss(bookSourceUrl: String) {
        if (bookSourceUrl.isNotEmpty()) {
            demotedUrls.add(bookSourceUrl)
        }
    }

    fun snapshot(): Set<String> = demotedUrls.toSet()

    fun isDemoted(bookSourceUrl: String): Boolean = bookSourceUrl in demotedUrls

    /** Tests only. */
    fun clear() {
        demotedUrls.clear()
    }
}
