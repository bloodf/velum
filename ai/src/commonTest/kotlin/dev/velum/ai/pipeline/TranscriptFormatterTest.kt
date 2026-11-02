package dev.velum.ai.pipeline

import dev.velum.ai.engine.TranscriptSegment
import dev.velum.ai.engine.TranscriptionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TranscriptFormatterTest {
    @Test
    fun split_markers_separates_only_a_trailing_section_of_marker_bullets() {
        val markers = "## Markers\n- **0:05**\n- **1:05** Synthetic note"
        assertEquals("Synthetic words" to markers, TranscriptFormatter.splitMarkers("Synthetic words\n\n$markers\n"))
        assertEquals("" to markers, TranscriptFormatter.splitMarkers("$markers\n"))
        assertEquals("Synthetic words" to null, TranscriptFormatter.splitMarkers("Synthetic words"))
        val spoken = "## Markers\nSynthetic words after a heading"
        assertEquals(spoken to null, TranscriptFormatter.splitMarkers(spoken))
    }

    @Test
    fun promotes_speaker_lines_to_bold_markdown() {
        val md = TranscriptFormatter.toMarkdown(
            TranscriptionResult(
                text = "Speaker 1: Hello\nSpeaker 2: Hi there",
                engineId = "t",
                modelId = "m",
            ),
        )
        assertTrue(md.contains("**Speaker 1:**"))
        assertTrue(md.contains("**Speaker 2:**"))
    }

    @Test
    fun formats_segments_with_timestamps() {
        val md = TranscriptFormatter.toMarkdown(
            TranscriptionResult(
                text = "",
                segments = listOf(
                    TranscriptSegment(0, 1_500, "Bom dia", speakerId = "S1"),
                    TranscriptSegment(1_500, 3_000, "Vamos", speakerId = "S2"),
                ),
                engineId = "t",
                modelId = "m",
            ),
        )
        assertTrue(md.contains("**Speaker 1**"))
        assertTrue(md.contains("0:00"))
        assertTrue(md.contains("Bom dia"))
    }
}
