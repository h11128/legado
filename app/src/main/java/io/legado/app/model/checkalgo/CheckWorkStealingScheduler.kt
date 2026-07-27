package io.legado.app.model.checkalgo

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Host-local queues with work-stealing across hosts.
 */
class CheckWorkStealingScheduler<T> {

    private val queues = ConcurrentHashMap<String, ConcurrentLinkedQueue<T>>()
    private val remaining = AtomicInteger(0)

    fun offer(host: String, item: T) {
        remaining.incrementAndGet()
        queues.getOrPut(host) { ConcurrentLinkedQueue() }.offer(item)
    }

    fun offerAll(items: Iterable<Pair<String, T>>) {
        for ((host, item) in items) offer(host, item)
    }

    suspend fun run(
        workers: Int,
        hostGate: (suspend (String) -> Unit)? = null,
        block: suspend (host: String, item: T) -> Unit,
    ) = coroutineScope {
        val done = AtomicBoolean(false)
        val stealCursor = AtomicInteger(0)

        fun steal(): Pair<String, T>? {
            val keys = queues.keys.toList()
            if (keys.isEmpty()) return null
            val start = stealCursor.getAndIncrement()
            for (i in keys.indices) {
                val host = keys[floorMod(start + i, keys.size)]
                val item = queues[host]?.poll() ?: continue
                return host to item
            }
            return null
        }

        repeat(workers.coerceAtLeast(1)) {
            launch {
                while (!done.get()) {
                    val pair = steal()
                    if (pair == null) {
                        if (remaining.get() <= 0) {
                            done.set(true)
                            break
                        }
                        delay(5)
                        continue
                    }
                    remaining.decrementAndGet()
                    val (host, item) = pair
                    hostGate?.invoke(host)
                    block(host, item)
                }
            }
        }
    }

    private fun floorMod(a: Int, m: Int): Int {
        val r = a % m
        return if (r >= 0) r else r + m
    }
}
