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
        // Consecutive windows share DEFAULT_WINDOW_SECONDS - DEFAULT_HOP_SECONDS of audio, so the words spoken there
        // come back at the start of the next window; drop that repeated run once, from the text and the segments.
        val texts = ArrayList<String>(parts.size)
        val segments = ArrayList<TranscriptSegment>()
        var soFar = ""
        parts.forEachIndexed { index, part ->
            val repeated = if (index == 0) 0 else overlapWordCount(soFar, part.text)
            val partText = dropLeadingWords(part.text, repeated)
            if (partText.isNotBlank()) {
                texts += partText
                soFar = (if (soFar.isEmpty()) "" else "$soFar ") + partText
                soFar = soFar.takeLast(OVERLAP_TAIL_CHARS)
            }
            val shift = windowStartMs[index]
            for (seg in dropLeadingSegmentWords(part.segments, repeated)) {
                segments += TranscriptSegment(
                    startMs = seg.startMs + shift,
                    endMs = seg.endMs + shift,
                    text = seg.text,
                    speakerId = seg.speakerId,
                    language = seg.language,
                )
            }
        }
        val text = texts.joinToString(" ")
        val first = parts.first()
        return TranscriptionResult(
            text = text,
            segments = segments,
            detectedLanguage = first.detectedLanguage,
            engineId = first.engineId,
            modelId = first.modelId,
        )
    }

    /** Longest run of repeated words considered (two seconds of speech is well under this). */
    private const val MAX_OVERLAP_WORDS = 12
    private const val OVERLAP_TAIL_CHARS = 400

    private fun words(text: String): List<String> = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    private fun norm(word: String): String =
        word.lowercase().filter { it.isLetterOrDigit() }.ifEmpty { word.lowercase() }

    /**
     * How many words at the start of [next] repeat the end of [previous] (the audio both windows share). A one-word
     * match counts only for a word of four or more characters, so a genuinely repeated short word ("the", "no") is
     * not taken for the overlap.
     */
    internal fun overlapWordCount(previous: String, next: String): Int {
        val tail = words(previous).takeLast(MAX_OVERLAP_WORDS).map(::norm)
        val head = words(next).take(MAX_OVERLAP_WORDS).map(::norm)
        for (k in minOf(tail.size, head.size) downTo 1) {
            if (tail.subList(tail.size - k, tail.size) == head.subList(0, k)) {
                return if (k >= 2 || head[0].length >= 4) k else 0
            }
        }
        return 0
    }

    private fun dropLeadingWords(text: String, count: Int): String =
        if (count <= 0) text.trim() else words(text).drop(count).joinToString(" ")

    /** Removes [count] leading words across [segments]: whole per-word segments, or words from a window-long one. */
    private fun dropLeadingSegmentWords(segments: List<TranscriptSegment>, count: Int): List<TranscriptSegment> {
        var left = count
        val out = ArrayList<TranscriptSegment>(segments.size)
        for (seg in segments) {
            if (left <= 0) {
                out += seg
                continue
            }
            val segWords = words(seg.text)
            if (segWords.size <= left) {
                left -= segWords.size
                continue
            }
            out += seg.copy(text = segWords.drop(left).joinToString(" "))
            left = 0
        }
        return out
    }
}

