package dev.velum.ai.pipeline

import dev.velum.ai.engine.DiarizationResult
import dev.velum.ai.engine.SpeakerSpan
import dev.velum.ai.engine.TranscriptSegment
import dev.velum.ai.engine.TranscriptionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** #365: generated speaker labels start at 1, as stored and as exported. */
class SpeakerNumberingTest {
    @Test fun the_first_cluster_is_speaker_1() {
        assertEquals("S1", TranscriptFormatter.speakerIdForCluster(0))
        assertEquals("S3", TranscriptFormatter.speakerIdForCluster(2))
    }

    @Test fun a_diarizer_that_numbers_clusters_from_zero_gives_speaker_1_and_2_in_the_markdown() {
        // What the Android diarizer emits: spans labelled through speakerIdForCluster for clusters 0 and 1.
        val diarization = DiarizationResult(
            spans = listOf(
                SpeakerSpan(TranscriptFormatter.speakerIdForCluster(0), 0, 2_000),
                SpeakerSpan(TranscriptFormatter.speakerIdForCluster(1), 2_000, 4_000),
            ),
            engineId = "d", modelId = "m",
        )
        val raw = TranscriptionResult(
            text = "a b",
            segments = listOf(TranscriptSegment(0, 1_900, "a"), TranscriptSegment(2_100, 3_900, "b")),
            engineId = "e", modelId = "m",
        )
        val merged = TranscriptFormatter.mergeSpeakerLabels(raw.segments, diarization)
        val md = TranscriptFormatter.toMarkdown(raw.copy(segments = merged), diarization)
        assertTrue(md.contains("**Speaker 1** · 0:00") && md.contains("**Speaker 2** · 0:02"), md)
        assertFalse(md.contains("Speaker 0"), md)
    }
}
