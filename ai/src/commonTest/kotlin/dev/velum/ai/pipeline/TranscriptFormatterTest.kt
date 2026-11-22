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

    private fun words(speaker: String?, startMs: Long, vararg w: String) =
        w.mapIndexed { i, word -> TranscriptSegment(startMs + i * 300L, startMs + i * 300L + 250, word, speakerId = speaker) }

    @Test
    fun word_level_segments_read_as_lines_not_one_word_per_line() {
        val md = TranscriptFormatter.toMarkdown(
            TranscriptionResult(text = "", segments = words("S1", 0, "we", "need", "to", "finish", "the", "report"), engineId = "t", modelId = "m"),
        )
        assertEquals("**Speaker 1** · 0:00\nwe need to finish the report", md)
    }

    @Test
    fun a_pause_or_a_new_speaker_starts_a_new_line() {
        val md = TranscriptFormatter.toMarkdown(
            TranscriptionResult(
                text = "",
                segments = words("S1", 0, "first", "thought") + words("S1", 5_000, "after", "a", "pause") + words("S2", 7_000, "reply"),
                engineId = "t",
                modelId = "m",
            ),
        )
        assertEquals("**Speaker 1** · 0:00\nfirst thought\nafter a pause\n\n**Speaker 2** · 0:07\nreply", md)
    }

    @Test
    fun window_long_segments_keep_a_line_each() {
        val a = "a".repeat(200)
        val b = "b".repeat(200)
        val md = TranscriptFormatter.toMarkdown(
            TranscriptionResult(
                text = "",
                segments = listOf(TranscriptSegment(0, 29_000, a), TranscriptSegment(29_500, 58_000, b)),
                engineId = "t",
                modelId = "m",
            ),
        )
        assertEquals("$a\n$b", md)
    }
}

