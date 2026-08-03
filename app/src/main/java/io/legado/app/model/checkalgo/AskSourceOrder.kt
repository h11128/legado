package io.legado.app.model.checkalgo

import io.legado.app.data.entities.BookSourcePart
import kotlin.math.min

/**
 * Ask-order for 换源 / search / auto 换源 (§6.2 RFC-001).
 *
 * Head reserve (by customOrder) then state rank → respondTime → customOrder.
 */
object AskSourceOrder {

    const val DEFAULT_HEAD_RESERVE = 8

    fun order(
        sources: List<BookSourcePart>,
        headReserve: Int = DEFAULT_HEAD_RESERVE,
        threadCount: Int,
    ): List<BookSourcePart> {
        if (sources.size <= 1) return sources
        val byCustom = sources.sortedBy { it.customOrder }
        val reserve = min(headReserve.coerceAtLeast(0), threadCount.coerceAtLeast(0))
            .coerceAtMost(byCustom.size)
        if (reserve <= 0) {
            return byCustom.sortedWith(restComparator)
        }
        if (reserve >= byCustom.size) {
            return byCustom
        }
        val head = byCustom.take(reserve)
        val rest = byCustom.drop(reserve).sortedWith(restComparator)
        return head + rest
    }

    private val restComparator = compareBy<BookSourcePart> {
        RespondTimeRank.classify(it.respondTime)
    }.thenBy {
        it.respondTime
    }.thenBy {
        it.customOrder
    }
}
