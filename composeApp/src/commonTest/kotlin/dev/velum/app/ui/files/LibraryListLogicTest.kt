package dev.velum.app.ui.files

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import dev.velum.core.db.Recording

class LibraryListLogicTest {

    @Test
    fun sort_newest_putsLaterStartedAtFirst() {
        val a = rec("a", title = "A", startedAt = 100)
        val b = rec("b", title = "B", startedAt = 200)
        val c = rec("c", title = "C", startedAt = 150)
        val sorted = LibraryListLogic.sorted(listOf(a, b, c), LibrarySort.Newest)
        assertEquals(listOf("b", "c", "a"), sorted.map { it.id })
    }

    @Test
    fun sort_oldest_putsEarlierStartedAtFirst() {
        val a = rec("a", title = "A", startedAt = 100)
        val b = rec("b", title = "B", startedAt = 200)
        val sorted = LibraryListLogic.sorted(listOf(b, a), LibrarySort.Oldest)
        assertEquals(listOf("a", "b"), sorted.map { it.id })
    }

    @Test
    fun sort_titleAsc_isCaseInsensitive() {
        val a = rec("1", title = "zeta", startedAt = 1)
        val b = rec("2", title = "Alpha", startedAt = 2)
        val c = rec("3", title = "beta", startedAt = 3)
        val sorted = LibraryListLogic.sorted(listOf(a, b, c), LibrarySort.TitleAsc)
        assertEquals(listOf("2", "3", "1"), sorted.map { it.id })
    }

    @Test
    fun sort_tiesBreakById() {
        val a = rec("b", title = "Same", startedAt = 10)
        val b = rec("a", title = "Same", startedAt = 10)
        val sorted = LibraryListLogic.sorted(listOf(a, b), LibrarySort.Newest)
        assertEquals(listOf("a", "b"), sorted.map { it.id })
    }

    @Test
    fun highlightRanges_emptyQuery_returnsEmpty() {
        assertTrue(LibraryListLogic.highlightRanges("Hello world", "").isEmpty())
        assertTrue(LibraryListLogic.highlightRanges("Hello world", "   ").isEmpty())
    }

    @Test
    fun highlightRanges_caseInsensitive_nonOverlapping() {
        val ranges = LibraryListLogic.highlightRanges("Budget Budget meeting", "budget")
        assertEquals(listOf(0..5, 7..12), ranges)
    }

    @Test
    fun highlightRanges_noMatch() {
        assertTrue(LibraryListLogic.highlightRanges("Standup notes", "budget").isEmpty())
    }

    @Test
    fun highlightedTitle_wrapsMatches() {
        val annotated = LibraryListLogic.highlightedTitle(
            title = "Q3 Budget review",
            query = "budget",
            highlightBackground = Color.Yellow,
            highlightForeground = Color.Black,
        )
        assertEquals("Q3 Budget review", annotated.text)
        assertEquals(1, annotated.spanStyles.size)
        assertEquals(3, annotated.spanStyles.first().start)
        assertEquals(9, annotated.spanStyles.first().end)
    }

    @Test
    fun recordingMarkdown_includesSections() {
        val md = LibraryListLogic.recordingMarkdown(
            title = "Sync",
            transcriptionLabel = "Transcription",
            summaryLabel = "Summary",
            transcript = "Hello",
            summaryMarkdown = "- Point",
        )
        assertTrue(md.startsWith("# Sync"))
        assertTrue(md.contains("## Transcription"))
        assertTrue(md.contains("Hello"))
        assertTrue(md.contains("## Summary"))
        assertTrue(md.contains("- Point"))
    }

    private fun rec(id: String, title: String, startedAt: Long): Recording =
        Recording(
            id = id,
            session_id = null,
            folder_id = null,
            title = title,
            source = "phone",
            device_serial = null,
            started_at = startedAt,
            duration_seconds = 60,
            size_bytes = 0,
            local_path = null,
            transcript = null,
            summary_markdown = null,
            status = "ready",
            trashed = 0,
            created_at = startedAt,
        )
}
