package io.legado.app.model.checkalgo

import java.util.TreeMap

/**
 * Consistent-hash ring for assigning URLs to check worker nodes (multi-device / PC orchestration).
 */
class CheckConsistentHash(
    nodes: List<String>,
    virtualNodes: Int = 64,
) {

    private val ring: TreeMap<Int, String> = buildRing(nodes, virtualNodes)

    init {
        require(nodes.isNotEmpty()) { "nodes must not be empty" }
        require(virtualNodes > 0) { "virtualNodes must be positive" }
    }

    fun nodeFor(url: String): String {
        val hash = hashKey(url)
        val entry = ring.ceilingEntry(hash) ?: ring.firstEntry()
        return entry.value
    }

    fun shard(urls: List<String>): Map<String, List<String>> {
        if (urls.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, MutableList<String>>()
        for (url in urls) {
            val node = nodeFor(url)
            out.getOrPut(node) { ArrayList() }.add(url)
        }
        return out
    }

    companion object {
        internal fun buildRing(nodes: List<String>, virtualNodes: Int): TreeMap<Int, String> {
            val ring = TreeMap<Int, String>()
            for (node in nodes.distinct()) {
                for (i in 0 until virtualNodes) {
                    val key = "$node#$i"
                    ring[hashKey(key)] = node
                }
            }
            return ring
        }

        /** Unsigned 32-bit hash for ring positions; shared with Bloom for deterministic sharding. */
        internal fun hashKey(text: String): Int {
            val bytes = text.toByteArray(Charsets.UTF_8)
            return CheckUrlBloom.murmur3_32(bytes, 0)
        }
    }
}
