package io.legado.app.model.webBook

import io.legado.app.data.entities.SearchBook

/**
 * Search-result merge identity.
 *
 * Baseline: same [SearchBook.name] and same [SearchBook.author].
 *
 * Empty-author rule (user: options 3+1):
 * - If the same name has **exactly one** distinct non-empty author among peers
 *   (plus the two books being compared), an empty-author hit may merge into that
 *   sole-author row (and vice versa).
 * - If the same name has **two or more** distinct non-empty authors, empty-author
 *   hits stay separate (do not guess which book).
 *
 * Note: merge runs online as sources stream in, so "unique author" is evaluated
 * against the **current** bucket. An empty hit merged early into author A is not
 * split later if author B arrives for the same title.
 */
object SearchBookMerge {

    fun sameBookForMerge(
        existing: SearchBook,
        incoming: SearchBook,
        peers: List<SearchBook>,
    ): Boolean {
        if (existing.name != incoming.name) return false
        val a1 = existing.author.trim()
        val a2 = incoming.author.trim()
        if (a1 == a2) return true
        if (a1.isNotEmpty() && a2.isNotEmpty()) return false

        val authors = linkedSetOf<String>()
        for (peer in peers) {
            if (peer.name != existing.name) continue
            val a = peer.author.trim()
            if (a.isNotEmpty()) authors.add(a)
        }
        if (a1.isNotEmpty()) authors.add(a1)
        if (a2.isNotEmpty()) authors.add(a2)
        if (authors.size != 1) return false
        val sole = authors.first()
        return (a1.isEmpty() || a1 == sole) && (a2.isEmpty() || a2 == sole)
    }

    /** Fold [incoming] into [target]: origins + keep non-empty author. */
    fun absorb(target: SearchBook, incoming: SearchBook) {
        target.addOrigin(incoming.origin)
        if (target.author.isBlank() && incoming.author.isNotBlank()) {
            target.author = incoming.author
        }
        if (target.intro.isNullOrBlank() && !incoming.intro.isNullOrBlank()) {
            target.intro = incoming.intro
        }
        if (target.coverUrl.isNullOrBlank() && !incoming.coverUrl.isNullOrBlank()) {
            target.coverUrl = incoming.coverUrl
        }
    }
}
