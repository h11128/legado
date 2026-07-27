package io.legado.app.model.checkalgo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckConsistentHashTest {

    private val nodes = listOf("phone-a", "phone-b", "pc-1")

    @Test
    fun nodeForIsDeterministic() {
        val ring = CheckConsistentHash(nodes)
        val url = "https://books.example/toc/42"
        assertEquals(ring.nodeFor(url), ring.nodeFor(url))
    }

    @Test
    fun shardGroupsAllUrls() {
        val ring = CheckConsistentHash(nodes)
        val urls = (0 until 30).map { "https://host.example/book/$it" }
        val grouped = ring.shard(urls)
        assertEquals(urls.size, grouped.values.sumOf { it.size })
        for (url in urls) {
            assertTrue(grouped.values.any { url in it })
        }
    }

    @Test
    fun shardPreservesInputOrderWithinNode() {
        val ring = CheckConsistentHash(listOf("only-node"), virtualNodes = 4)
        val urls = listOf("u1", "u2", "u3")
        val grouped = ring.shard(urls)
        assertEquals(urls, grouped["only-node"])
    }

    @Test
    fun singleNodeGetsEverything() {
        val ring = CheckConsistentHash(listOf("solo"))
        val urls = listOf("a", "b", "c")
        val grouped = ring.shard(urls)
        assertEquals(1, grouped.size)
        assertEquals(urls, grouped["solo"])
    }

    @Test
    fun emptyUrlListReturnsEmptyMap() {
        val ring = CheckConsistentHash(nodes)
        assertEquals(emptyMap<String, List<String>>(), ring.shard(emptyList()))
    }

    @Test
    fun distributionUsesMultipleNodes() {
        val ring = CheckConsistentHash(nodes, virtualNodes = 64)
        val urls = (0 until 300).map { "https://dist.example/$it" }
        val grouped = ring.shard(urls)
        assertTrue("expected multiple nodes to receive URLs", grouped.size >= 2)
    }

    @Test
    fun hashKeyMatchesBloomMurmur() {
        val text = "phone-a#0"
        val bytes = text.toByteArray(Charsets.UTF_8)
        assertEquals(CheckUrlBloom.murmur3_32(bytes, 0), CheckConsistentHash.hashKey(text))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptyNodes() {
        CheckConsistentHash(emptyList())
    }
}
