package com.wasimaster.wmkeyboard.app

import androidx.compose.ui.unit.Constraints
import com.wasimaster.wmkeyboard.core.snippets.Snippet
import org.junit.Assert.*
import org.junit.Test

class SnippetPagingTest {
    private val imported = List(1500) { Snippet(it.toLong(), "item$it", "text$it") }
    @Test fun `all 1500 imported snippets remain accessible in bounded pages`() {
        assertEquals(30, snippetPageCount(imported.size))
        val pages = (0 until snippetPageCount(imported.size)).map { snippetPage(imported, it) }
        assertTrue(pages.all { it.size == 50 })
        assertEquals(imported, pages.flatten())
        assertEquals(pages.last(), snippetPage(imported, 1000))
    }
    @Test fun `page deletion clamps safely and an empty store has no rows`() {
        assertTrue(snippetPage(emptyList(), 99).isEmpty())
        assertEquals(1, snippetPageCount(0))
        assertEquals(imported.take(3), snippetPage(imported.take(3), 29))
        assertEquals(1, snippetPage(imported.take(51), 1).size)
    }
    @Test fun `reordering a page never removes or changes the other 1450 snippets`() {
        val page = snippetPage(imported, 9)
        val result = reorderedSnippetPage(imported, page.reversed())
        assertEquals(imported.take(450), result.take(450))
        assertEquals(page.reversed(), result.subList(450, 500))
        assertEquals(imported.drop(500), result.drop(500))
        assertEquals(imported.map { it.id }.toSet(), result.map { it.id }.toSet())
    }
    @Test fun `original 1500 row import reproduces the recorded constraint crash`() {
        // 1500 imported rows minus the 12 eager rows leaves this placeholder.
        val originalPixels = kotlin.math.round((1488 * 72 + 1487 * 3 + 16) * 3.5f).toInt()
        assertEquals(390646, originalPixels)
        try {
            Constraints.fixedHeight(originalPixels)
            fail("The original placeholder must exceed Compose's constraint encoding")
        } catch (_: IllegalArgumentException) { }
        Constraints.fixedHeight((boundedSkeletonRows(1488) * 75 * 3.5f).toInt())
    }
    @Test fun `large placeholder dimensions remain representable on high density screens`() {
        for (count in listOf(0, 1, 1500, Int.MAX_VALUE)) for (density in listOf(1f, 3.5f, 6f)) {
            val rows = boundedSkeletonRows(count)
            val pixels = ((32 + rows * 75 + 16) * density).toInt()
            val constraints = Constraints.fixedHeight(pixels)
            assertEquals(pixels, constraints.maxHeight)
        }
    }
}
