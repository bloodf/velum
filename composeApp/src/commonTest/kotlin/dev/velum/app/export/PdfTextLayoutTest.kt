package dev.velum.app.export

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PdfTextLayoutTest {
    @Test
    fun wrapLine_breaksOnSpacesWhenPossible() {
        val lines = PdfTextLayout.wrapLine("hello world from velum export", 12)
        assertEquals(listOf("hello world", "from velum", "export"), lines)
    }

    @Test
    fun wrapLine_hardBreaksLongTokens() {
        val lines = PdfTextLayout.wrapLine("abcdefghij", 4)
        assertEquals(listOf("abcd", "efgh", "ij"), lines)
    }

    @Test
    fun wrapBody_preservesBlankLinesAsEmpty() {
        val lines = PdfTextLayout.wrapBody("a\n\nb", 40)
        assertEquals(listOf("a", "", "b"), lines)
    }

    @Test
    fun paginate_chunksByLinesPerPage() {
        val pages = PdfTextLayout.paginate(listOf("1", "2", "3", "4", "5"), 2)
        assertEquals(3, pages.size)
        assertEquals(listOf("1", "2"), pages[0])
        assertEquals(listOf("5"), pages[2])
    }

    @Test
    fun wrapBody_blankYieldsPlaceholder() {
        val lines = PdfTextLayout.wrapBody("   ", 40)
        assertTrue(lines.isNotEmpty())
        assertEquals(" ", lines.first())
    }
}
