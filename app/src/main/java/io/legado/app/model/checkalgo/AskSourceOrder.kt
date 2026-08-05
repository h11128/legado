package io.legado.app.model.checkalgo

import io.legado.app.data.entities.BookSourcePart
import kotlin.math.min

/**
 * Ask-order for 换源 / search / auto 换源 (§6.2 RFC-001).
 *
 * Head reserve (by customOrder) then SUCCESS→UNKNOWN→FAILURE (via respondTime
 * numeric bands) → respondTime ASC → customOrder. Process-lifetime [demoteUrls]
 * are appended last (session miss / timeout / empty — never persisted as failure).
 */
object AskSourceOrder {

    const val DEFAULT_HEAD_RESERVE = 8

    fun order(
        sources: List<BookSourcePart>,
        headReserve: Int = DEFAULT_HEAD_RESERVE,
        threadCount: Int,
        demoteUrls: Set<String> = ChangeSourceAskMemory.snapshot(),
    ): List<BookSourcePart> {
        if (sources.size <= 1) return sources
        val (active, demoted) = sources.partition { it.bookSourceUrl !in demoteUrls }
        if (active.isEmpty()) {
            return demoted.sortedWith(restComparator)
        }
        val orderedActive = orderActive(active, headReserve, threadCount)
        if (demoted.isEmpty()) return orderedActive
        return orderedActive + demoted.sortedWith(restComparator)
    }

    private fun orderActive(
        sources: List<BookSourcePart>,
        headReserve: Int,
        threadCount: Int,
    ): List<BookSourcePart> {
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

    /**
     * respondTime already encodes SUCCESS < UNKNOWN < FAILURE (§6.1);
     * explicit classify keeps the comparator readable and RFC-aligned.
     */
    private val restComparator = compareBy<BookSourcePart> {
        RespondTimeRank.classify(it.respondTime)
    }.thenBy {
        it.respondTime
    }.thenBy {
        it.customOrder
    }
}
