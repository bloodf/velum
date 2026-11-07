package dev.velum.ai.pipeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShareMarkdownBodyTest {
    private val markers = "## Markers\n- **0:12** budget\n"

    @Test
    fun markersOnly_keepsMarkers_withoutATranscriptHeading() {
        val body = shareMarkdownBody("Standup", "## Summary\nok", markers)
        assertFalse(body.contains("## Transcript"), body)
        assertTrue(body.contains("## Markers\n- **0:12** budget"), body)
    }

    @Test
    fun spokenTextAndMarkers_areSeparateSections() {
        val body = shareMarkdownBody("Standup", null, "Hello there.\n\n$markers")
        assertEquals("# Standup\n\n## Transcript\n\nHello there.\n\n## Markers\n- **0:12** budget", body)
    }

    @Test
    fun plainTranscript_isUnchanged() {
        assertEquals("# T\n\nS\n\n## Transcript\n\nspoken", shareMarkdownBody("T", "S", "spoken"))
        assertEquals("# T\n\n", shareMarkdownBody("T", null, "  "))
    }
}
