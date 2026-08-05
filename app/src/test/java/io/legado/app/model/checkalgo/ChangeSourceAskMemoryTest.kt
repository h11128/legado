package io.legado.app.model.checkalgo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChangeSourceAskMemoryTest {

    @Before
    fun reset() {
        ChangeSourceAskMemory.clear()
    }

    @Test
    fun titleEmptyIsScopedPerBook() {
        ChangeSourceAskMemory.noteTitleEmpty("书A", "作者", "https://a.com")
        assertTrue(ChangeSourceAskMemory.isTitleEmpty("书A", "作者", "https://a.com"))
        assertFalse(ChangeSourceAskMemory.isTitleEmpty("书B", "作者", "https://a.com"))
        assertFalse(ChangeSourceAskMemory.isDemoted("https://a.com"))
    }

    @Test
    fun globalDemoteDoesNotAffectTitleEmpty() {
        ChangeSourceAskMemory.noteMiss("https://b.com")
        assertTrue(ChangeSourceAskMemory.isDemoted("https://b.com"))
        assertFalse(ChangeSourceAskMemory.isTitleEmpty("书A", "作者", "https://b.com"))
    }
}
