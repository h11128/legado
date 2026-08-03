package io.legado.app.model.checkalgo

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-ask-session fail skip list. Create one instance per 换源 / search / auto-change run
 * so concurrent ask paths do not clear or share each other's counters.
 */
class AskFailCooldown {

    companion object {
        const val FAIL_THRESHOLD = 3
    }

    private val fails = ConcurrentHashMap<String, Int>()

    fun shouldSkip(url: String): Boolean {
        return (fails[url] ?: 0) >= FAIL_THRESHOLD
    }

    fun noteFail(url: String) {
        fails.merge(url, 1) { prev, _ -> prev + 1 }
    }

    fun noteSuccess(url: String) {
        fails.remove(url)
    }

    fun clear() {
        fails.clear()
    }

    fun failCount(url: String): Int {
        return fails[url] ?: 0
    }
}
