package dev.velum.ai.pipeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecordingProcessorMapReduceTest {
    @Test
    fun chunk_text_splits_on_newlines_when_possible() {
        val text = (1..40).joinToString("\n") { "line-$it-xxxxxxxx" }
        val chunks = RecordingProcessor.chunkText(text, chunkChars = 80)
        assertTrue(chunks.size > 1)
        assertEquals(text, chunks.joinToString(""))
    }

    @Test
    fun short_text_stays_single_chunk() {
        assertEquals(listOf("hi"), RecordingProcessor.chunkText("hi", 100))
    }
}
