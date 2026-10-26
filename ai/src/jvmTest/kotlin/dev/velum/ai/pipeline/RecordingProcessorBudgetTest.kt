package dev.velum.ai.pipeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecordingProcessorBudgetTest {
    @Test
    fun max_audio_bytes_caps_near_200_mib() {
        assertEquals(200_000_000L, RecordingProcessor.MAX_AUDIO_BYTES)
        assertTrue(RecordingProcessor.MAX_AUDIO_BYTES < Int.MAX_VALUE.toLong())
    }
}
