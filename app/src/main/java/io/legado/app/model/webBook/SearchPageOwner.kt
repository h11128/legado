package io.legado.app.model.webBook

import kotlinx.coroutines.Job

internal class SearchPageOwner {
    private var owner: Job? = null

    @Synchronized
    fun isRunning(): Boolean = owner != null

    @Synchronized
    fun register(job: Job): Boolean {
        if (owner != null) return false
        owner = job
        return true
    }

    @Synchronized
    fun complete(job: Job?, onComplete: () -> Unit): Boolean {
        if (job == null || owner !== job) return false
        owner = null
        onComplete()
        return true
    }

    @Synchronized
    fun cancel(): Job? {
        val job = owner
        owner = null
        return job
    }
}

internal class SearchProgressReporter(
    total: Int,
    private val onProgress: (searched: Int, total: Int) -> Unit,
) {
    private val total = total.coerceAtLeast(0)
    private var completed = 0
    private var active = true
    private var started = false

    @Synchronized
    fun start(onStart: () -> Unit = {}) {
        if (!active || started) return
        started = true
        onStart()
        onProgress(0, total)
    }

    @Synchronized
    fun completeOne() {
        if (!active || !started || completed >= total) return
        completed++
        onProgress(completed, total)
    }

    @Synchronized
    fun finish(onFinish: () -> Unit) {
        if (!active || !started) return
        active = false
        onFinish()
    }

    @Synchronized
    fun cancel() {
        active = false
    }
}
