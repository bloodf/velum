package dev.velum.core.integrations

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarkdownNoteExportTest {
    @Test
    fun render_includesFrontmatterAndSections() {
        val md = MarkdownNoteExport.render(
            MarkdownNoteExport.Note(
                title = "Weekly sync: budget",
                startedAtEpochSeconds = 1_729_939_155L,
                durationSeconds = 3600,
                source = "note_pro",
                speakers = listOf("Ada", "Bob"),
                tags = listOf("meeting", "velum"),
                summaryMarkdown = "## Overview\n- Decided budget",
                transcriptMarkdown = "**Ada** (0:00)\nHello",
            ),
        )
        assertTrue(md.startsWith("---\n"))
        assertContains(md, "title: \"Weekly sync: budget\"")
        assertContains(md, "duration_seconds: 3600")
        assertContains(md, "speakers:")
        assertContains(md, "  - Ada")
        assertContains(md, "## Overview")
        assertContains(md, "## Transcript")
        assertContains(md, "app: Velum")
    }

    @Test
    fun filename_isStableSlug() {
        val name = MarkdownNoteExport.filename("Weekly Sync!", 100L)
        assertTrue(name.startsWith("100-"))
        assertTrue(name.endsWith(".md"))
        assertFalse(name.contains(" "))
        assertFalse(name.contains("!"))
    }
}
