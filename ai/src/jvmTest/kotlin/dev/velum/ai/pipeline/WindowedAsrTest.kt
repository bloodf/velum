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

    private fun part(text: String, segments: List<TranscriptSegment> = emptyList()) =
        TranscriptionResult(text = text, segments = segments, engineId = "t", modelId = "m")

    @Test
    fun words_heard_in_both_overlapping_windows_appear_once() {
        val merged = WindowedAsr.mergeWindowResults(
            listOf(part("we need to finish the"), part("finish the report by Friday")),
            listOf(0L, 28_000L),
        )
        assertEquals("we need to finish the report by Friday", merged.text)
    }

    @Test
    fun case_and_punctuation_do_not_hide_the_overlap() {
        val merged = WindowedAsr.mergeWindowResults(
            listOf(part("So we agreed. Let's go."), part("let's go to lunch")),
            listOf(0L, 28_000L),
        )
        assertEquals("So we agreed. Let's go. to lunch", merged.text)
    }

    @Test
    fun text_without_a_shared_run_is_kept_whole() {
        val merged = WindowedAsr.mergeWindowResults(listOf(part("hello world"), part("goodbye moon")), listOf(0L, 28_000L))
        assertEquals("hello world goodbye moon", merged.text)
    }

    @Test
    fun a_single_short_repeated_word_is_not_taken_for_the_overlap() {
        assertEquals(0, WindowedAsr.overlapWordCount("over there by the", "the cat sat"))
        assertEquals(1, WindowedAsr.overlapWordCount("we ship it tomorrow", "Tomorrow, we ship"))
    }

    @Test
    fun a_window_long_segment_loses_the_repeated_words_and_keeps_its_timing() {
        val merged = WindowedAsr.mergeWindowResults(
            listOf(
                part("we need to finish the", listOf(TranscriptSegment(100, 29_900, "we need to finish the"))),
                part("finish the report by Friday", listOf(TranscriptSegment(50, 4_000, "finish the report by Friday"))),
            ),
            listOf(0L, 28_000L),
        )
        assertEquals(listOf("we need to finish the", "report by Friday"), merged.segments.map { it.text })
        assertEquals(28_050L, merged.segments[1].startMs)
    }

    @Test
    fun per_word_segments_drop_only_the_repeated_words() {
        fun wordsAt(offset: Long, vararg w: String) = w.mapIndexed { i, word -> TranscriptSegment(offset + i * 300L, offset + i * 300L + 250, word) }
        val merged = WindowedAsr.mergeWindowResults(
            listOf(
                part("finish the", wordsAt(29_000, "finish", "the")),
                part("finish the report", wordsAt(1_000, "finish", "the", "report")),
            ),
            listOf(0L, 28_000L),
        )
        assertEquals(listOf("finish", "the", "report"), merged.segments.map { it.text })
        assertEquals(29_600L, merged.segments[2].startMs)
    }
}

