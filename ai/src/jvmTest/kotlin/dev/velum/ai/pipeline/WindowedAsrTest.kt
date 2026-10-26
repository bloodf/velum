package dev.velum.ai.pipeline

import dev.velum.ai.engine.TranscriptSegment
import dev.velum.ai.engine.TranscriptionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WindowedAsrTest {
    @Test
    fun short_pcm_is_single_window() {
        val bytes = 16_000 * 2 * 10 // 10s
        val windows = WindowedAsr.windows(bytes, sampleRateHz = 16_000)
        assertEquals(1, windows.size)
        assertEquals(0, windows[0].startByte)
        assertEquals(bytes, windows[0].endByte)
    }

    @Test
    fun long_pcm_splits_into_overlapping_windows() {
        val bytes = 16_000 * 2 * 90 // 90s > 30s threshold
        val windows = WindowedAsr.windows(bytes, sampleRateHz = 16_000)
        assertTrue(windows.size >= 3, "expected multiple windows, got ${windows.size}")
        assertEquals(0, windows.first().startByte)
        assertEquals(bytes, windows.last().endByte)
        assertTrue(windows[1].startByte > 0)
        assertTrue(windows[1].startByte < windows[0].endByte)
    }

    @Test
    fun stitch_and_merge_shift_timestamps() {
        val md = WindowedAsr.stitchTranscriptMarkdown(listOf("hello", "  ", "world"))
        assertEquals("hello\n\nworld", md)
        val merged = WindowedAsr.mergeWindowResults(
            parts = listOf(
                TranscriptionResult(
                    text = "a",
                    segments = listOf(TranscriptSegment(0, 500, "a")),
                    engineId = "t",
                    modelId = "m",
                ),
                TranscriptionResult(
                    text = "b",
                    segments = listOf(TranscriptSegment(0, 400, "b")),
                    engineId = "t",
                    modelId = "m",
                ),
            ),
            windowStartMs = listOf(0L, 30_000L),
        )
        assertEquals("a b", merged.text)
        assertEquals(30_000L, merged.segments[1].startMs)
        assertEquals(30_400L, merged.segments[1].endMs)
    }
}
