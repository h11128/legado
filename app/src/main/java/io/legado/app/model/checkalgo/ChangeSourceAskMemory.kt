package io.legado.app.model.checkalgo

import java.util.concurrent.ConcurrentHashMap

/**
 * Process-lifetime ask demotion for 换源 / search / auto 换源.
 *
 * Empty / timeout / content-bad probes must **not** write [io.legado.app.data.entities.BookSource.respondTime]
 * (RFC-001 §6.4). Global demotion only moves URLs to the end of [AskSourceOrder] for later asks.
 *
 * Title-scoped empties: a source that returned no hit for book (name, author) is skipped on
 * later asks for the **same title** in this process (no network). Empty stays out of the
 * global demote set so other books can still find that source.
 */
object ChangeSourceAskMemory {

    private val demotedUrls = ConcurrentHashMap.newKeySet<String>()

    /** titleKey → origins that returned empty search for that book. */
    private val titleEmptyOrigins =
        ConcurrentHashMap<String, MutableSet<String>>()

    fun noteMiss(bookSourceUrl: String) {
        if (bookSourceUrl.isNotEmpty()) {
            demotedUrls.add(bookSourceUrl)
        }
    }

    fun snapshot(): Set<String> = demotedUrls.toSet()

    fun isDemoted(bookSourceUrl: String): Boolean = bookSourceUrl in demotedUrls

    fun titleKey(name: String, author: String): String =
        "${name.trim()}\u0000${author.trim()}"

    fun noteTitleEmpty(name: String, author: String, bookSourceUrl: String) {
        if (bookSourceUrl.isEmpty()) return
        titleEmptyOrigins
            .getOrPut(titleKey(name, author)) { ConcurrentHashMap.newKeySet() }
            .add(bookSourceUrl)
    }

    fun isTitleEmpty(name: String, author: String, bookSourceUrl: String): Boolean =
        titleEmptyOrigins[titleKey(name, author)]?.contains(bookSourceUrl) == true

    fun titleEmptySnapshot(name: String, author: String): Set<String> =
        titleEmptyOrigins[titleKey(name, author)]?.toSet().orEmpty()

    /** Tests only. */
    fun clear() {
        demotedUrls.clear()
        titleEmptyOrigins.clear()
    }
}
