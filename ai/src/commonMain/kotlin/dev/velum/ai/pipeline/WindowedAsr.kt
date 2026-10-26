package dev.velum.ai.pipeline

import dev.velum.ai.engine.TranscriptSegment
import dev.velum.ai.engine.TranscriptionResult

/**
 * Window helpers for offline ASR so long PCM is not held as one float array forever
 * during recognition (still one ByteArray on the heap for decode — see Y14).
 * Patterns match OverlappingChunkedOfflineStreamingTranscriber (window / hop).
 */
object WindowedAsr {
    const val DEFAULT_WINDOW_SECONDS: Int = 30
    const val DEFAULT_HOP_SECONDS: Int = 28
    const val WINDOW_THRESHOLD_BYTES: Int = 16_000 * 2 * DEFAULT_WINDOW_SECONDS

    data class ByteWindow(
        val startByte: Int,
        val endByte: Int,
        val startMs: Long,
    )

    fun pcmWindowBytes(sampleRateHz: Int, windowSeconds: Int): Int =
        sampleRateHz.coerceAtLeast(1) * 2 * windowSeconds.coerceAtLeast(1)

    fun pcmHopBytes(sampleRateHz: Int, hopSeconds: Int): Int =
        sampleRateHz.coerceAtLeast(1) * 2 * hopSeconds.coerceAtLeast(1)

    fun windows(
        totalPcmBytes: Int,
        sampleRateHz: Int,
        windowSeconds: Int = DEFAULT_WINDOW_SECONDS,
        hopSeconds: Int = DEFAULT_HOP_SECONDS,
    ): List<ByteWindow> {
        val even = totalPcmBytes - (totalPcmBytes % 2)
        if (even <= 0) return emptyList()
        val window = pcmWindowBytes(sampleRateHz, windowSeconds)
        val hop = pcmHopBytes(sampleRateHz, hopSeconds).coerceAtLeast(2)
        if (even <= window) {
            return listOf(ByteWindow(0, even, 0L))
        }
        val out = ArrayList<ByteWindow>()
        var start = 0
        while (start < even) {
            val end = (start + window).coerceAtMost(even)
            val startMs = if (sampleRateHz <= 0) 0L else (start / 2L) * 1000L / sampleRateHz
            out += ByteWindow(start, end, startMs)
            if (end >= even) break
            start += hop
            if (start >= even) break
        }
        if (out.isNotEmpty() && out.last().endByte < even) {
            val start = (even - window).coerceAtLeast(0)
            val startMs = if (sampleRateHz <= 0) 0L else (start / 2L) * 1000L / sampleRateHz
            if (out.none { it.startByte == start && it.endByte == even }) {
                out += ByteWindow(start, even, startMs)
            }
        }
        return out
    }

    fun stitchTranscriptMarkdown(parts: List<String>): String =
        parts.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n\n")

    fun mergeWindowResults(
        parts: List<TranscriptionResult>,
        windowStartMs: List<Long>,
    ): TranscriptionResult {
        require(parts.size == windowStartMs.size) {
            "parts (${parts.size}) and windowStartMs (${windowStartMs.size}) size mismatch"
        }
        if (parts.isEmpty()) {
            return TranscriptionResult(text = "", segments = emptyList(), engineId = "", modelId = "")
        }
        val text = parts.map { it.text.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        val segments = ArrayList<TranscriptSegment>()
        parts.forEachIndexed { index, part ->
            val shift = windowStartMs[index]
            for (seg in part.segments) {
                segments += TranscriptSegment(
                    startMs = seg.startMs + shift,
                    endMs = seg.endMs + shift,
                    text = seg.text,
                    speakerId = seg.speakerId,
                    language = seg.language,
                )
            }
        }
        val first = parts.first()
        return TranscriptionResult(
            text = text,
            segments = segments,
            detectedLanguage = first.detectedLanguage,
            engineId = first.engineId,
            modelId = first.modelId,
        )
    }
}
