package dev.velum.core.data

import dev.velum.core.autoflow.AutoFlowMatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TranscriptMarkersTest {
    private val markers = "## Markers\n- **1:05** budget decision"

    @Test fun spoken_drops_only_the_trailing_markers_section() {
        assertEquals("Synthetic words", TranscriptMarkers.spoken("Synthetic words\n\n$markers"))
        assertEquals("", TranscriptMarkers.spoken(markers))
        assertNull(TranscriptMarkers.spoken(null))
        val prose = "A heading follows\n\n## Markers\nnot a marker list"
        assertEquals(prose, TranscriptMarkers.spoken(prose))
    }

    // #205: an automation rule must not fire on text the user typed as a marker.
    @Test fun a_wake_phrase_inside_a_marker_does_not_trigger_an_autoflow_rule() {
        val rule = AutoFlowMatcher.Rule(
            enabled = true, wakePhrase = "budget decision", durationMinSeconds = 0,
            durationMaxSeconds = 3600, sourceFilter = "all", templateId = "velum.meeting",
        )
        assertFalse(
            AutoFlowMatcher.matches(rule, AutoFlowMatcher.Candidate(source = "phone", durationSeconds = 60, transcript = "Hello there\n\n$markers")),
        )
        assertTrue(
            AutoFlowMatcher.matches(rule, AutoFlowMatcher.Candidate(source = "phone", durationSeconds = 60, transcript = "We made a budget decision today")),
        )
    }
}
