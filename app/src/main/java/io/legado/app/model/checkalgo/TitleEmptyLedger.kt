package io.legado.app.model.checkalgo

import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory title→(origin→notedAtMs) ledger with TTL.
 * Persistence is optional via [ChangeSourceTitleEmptyPrefs].
 */
class TitleEmptyLedger(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val byTitle = ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>()

    fun note(titleKey: String, url: String) {
        if (titleKey.isEmpty() || url.isEmpty()) return
        byTitle.getOrPut(titleKey) { ConcurrentHashMap() }[url] = clock()
    }

    fun isEmpty(titleKey: String, url: String): Boolean {
        val map = byTitle[titleKey] ?: return false
        val ts = map[url] ?: return false
        if (clock() - ts > ttlMs) {
            map.remove(url, ts)
            return false
        }
        return true
    }

    fun snapshot(titleKey: String): Map<String, Long> {
        val map = byTitle[titleKey] ?: return emptyMap()
        val now = clock()
        val live = map.filterValues { now - it <= ttlMs }
        if (live.size != map.size) {
            map.keys.retainAll(live.keys)
        }
        return live
    }

    /** Merge disk rows (already filtered by caller or pruned here). */
    fun importEntries(titleKey: String, entries: Map<String, Long>) {
        if (titleKey.isEmpty() || entries.isEmpty()) return
        val now = clock()
        val live = entries.filterValues { now - it <= ttlMs }
        if (live.isEmpty()) return
        val map = byTitle.getOrPut(titleKey) { ConcurrentHashMap() }
        live.forEach { (url, ts) ->
            map.merge(url, ts) { a, b -> maxOf(a, b) }
        }
    }

    fun clear() {
        byTitle.clear()
    }

    companion object {
        /** 7 days — sites can recover; user can still re-ask after TTL. */
        const val DEFAULT_TTL_MS = 7L * 24 * 60 * 60 * 1000
    }
}
