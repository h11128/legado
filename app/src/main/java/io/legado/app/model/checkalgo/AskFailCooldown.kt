package io.legado.app.model.checkalgo

import java.util.concurrent.ConcurrentHashMap

/**
 * Skip sources that fail [FAIL_THRESHOLD] times within the current ask session.
 * Call [clear] when starting a new 换源 / search / auto-change session.
 */
object AskFailCooldown {

    const val FAIL_THRESHOLD = 3

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
