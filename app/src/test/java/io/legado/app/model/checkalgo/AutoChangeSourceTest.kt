package io.legado.app.model.checkalgo

import io.legado.app.data.entities.BookSourcePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoChangeSourceTest {

    @Test
    fun filterParts_excludesCurrentOrigin() {
        val parts = listOf(
            BookSourcePart(bookSourceUrl = "https://dead.example/"),
            BookSourcePart(bookSourceUrl = "https://good.example/"),
            BookSourcePart(bookSourceUrl = "https://other.example/"),
        )
        val filtered = AutoChangeSource.filterParts(parts, "https://dead.example/")
        assertEquals(2, filtered.size)
        assertTrue(filtered.none { it.bookSourceUrl == "https://dead.example/" })
    }

    @Test
    fun filterParts_blankExcludeKeepsAll() {
        val parts = listOf(
            BookSourcePart(bookSourceUrl = "a"),
            BookSourcePart(bookSourceUrl = "b"),
        )
        assertEquals(2, AutoChangeSource.filterParts(parts, null).size)
        assertEquals(2, AutoChangeSource.filterParts(parts, "  ").size)
    }
}
