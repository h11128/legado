package io.legado.app.model.checkalgo

import io.legado.app.data.entities.BookSourcePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AskSourcePrefetchTest {

    private fun part(url: String, order: Int = 0) = BookSourcePart(
        bookSourceUrl = url,
        bookSourceName = url,
        customOrder = order,
    )

    @Test
    fun hostOfParsesHttpsHost() {
        assertEquals("example.com", AskSourcePrefetch.hostOf("https://example.com/path?q=1"))
    }

    @Test
    fun hostOfParsesHttpWithPort() {
        assertEquals("10.0.0.1", AskSourcePrefetch.hostOf("http://10.0.0.1:8080/a"))
    }

    @Test
    fun hostOfReturnsEmptyOnInvalid() {
        assertEquals("", AskSourcePrefetch.hostOf(""))
        assertEquals("", AskSourcePrefetch.hostOf("not a uri :::"))
    }

    @Test
    fun hostOfRelativeHasNoHost() {
        assertEquals("", AskSourcePrefetch.hostOf("/relative/path"))
    }

    @Test
    fun chunkPartsEmpty() {
        assertTrue(AskSourcePrefetch.chunkParts(emptyList()).isEmpty())
    }

    @Test
    fun chunkPartsPreservesOrderAndSize() {
        val parts = (0 until 350).map { part("s$it", it) }
        val chunks = AskSourcePrefetch.chunkParts(parts)
        assertEquals(3, chunks.size)
        assertEquals(AskSourcePrefetch.PREFETCH_CHUNK_SIZE, chunks[0].size)
        assertEquals(AskSourcePrefetch.PREFETCH_CHUNK_SIZE, chunks[1].size)
        assertEquals(50, chunks[2].size)
        assertEquals(
            parts.map { it.bookSourceUrl },
            chunks.flatten().map { it.bookSourceUrl },
        )
    }

    @Test
    fun chunkPartsExactMultiple() {
        val parts = (0 until AskSourcePrefetch.PREFETCH_CHUNK_SIZE).map { part("s$it") }
        val chunks = AskSourcePrefetch.chunkParts(parts)
        assertEquals(1, chunks.size)
        assertEquals(AskSourcePrefetch.PREFETCH_CHUNK_SIZE, chunks[0].size)
    }
}
