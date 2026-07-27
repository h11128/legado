package io.legado.app.model

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.ArrayDeque
import java.util.LinkedHashMap

/**
 * Round-robin URLs across hosts so same-site sources are not clustered under concurrency.
 */
object CheckHostSharding {

    fun shardByHost(urls: List<String>): List<String> {
        if (urls.size <= 1) return urls
        val buckets = LinkedHashMap<String, ArrayDeque<String>>()
        for (url in urls) {
            val host = url.toHttpUrlOrNull()?.host ?: url
            buckets.getOrPut(host) { ArrayDeque() }.addLast(url)
        }
        if (buckets.size <= 1) return urls
        val out = ArrayList<String>(urls.size)
        while (buckets.isNotEmpty()) {
            val iter = buckets.entries.iterator()
            while (iter.hasNext()) {
                val entry = iter.next()
                out.add(entry.value.removeFirst())
                if (entry.value.isEmpty()) iter.remove()
            }
        }
        return out
    }
}
