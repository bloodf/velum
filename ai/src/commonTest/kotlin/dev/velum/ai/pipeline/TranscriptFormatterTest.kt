package dev.velum.ai.pipeline

import dev.velum.ai.engine.TranscriptSegment
import dev.velum.ai.engine.TranscriptionResult
import kotlin.test.Test
import kotlin.test.assertTrue

class TranscriptFormatterTest {
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
