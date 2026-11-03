package dev.velum.ai.pipeline

import dev.velum.ai.engine.TranscriptSegment
import dev.velum.ai.engine.TranscriptionResult
import kotlin.test.Test
import kotlin.test.assertTrue

/** #365: generated speaker labels start at 1, as stored and as exported. */
class SpeakerNumberingTest {
    @Test fun the_first_cluster_is_speaker_1() {
        assertTrue(TranscriptFormatter.speakerIdForCluster(0) == "S1")
        assertTrue(TranscriptFormatter.speakerIdForCluster(2) == "S3")
    }

    @Test fun markdown_for_the_first_cluster_says_speaker_1_never_0() {
        val md = TranscriptFormatter.toMarkdown(
            TranscriptionResult(
                text = "hi",
                segments = listOf(TranscriptSegment(0, 1000, "hi", speakerId = TranscriptFormatter.speakerIdForCluster(0))),
                engineId = "e", modelId = "m",
            ),
        )
        assertTrue(md.contains("**Speaker 1**") && !md.contains("Speaker 0"), md)
    }
}
