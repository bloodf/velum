package dev.velum.ai.pipeline

import dev.velum.ai.engine.DiarizationResult
import dev.velum.ai.engine.TranscriptSegment
import dev.velum.ai.engine.TranscriptionResult

object TranscriptFormatter {
    fun toMarkdown(
        transcription: TranscriptionResult,
        diarization: DiarizationResult? = null,
    ): String {
        val named = diarization?.namedSpeakers.orEmpty()
        val segments = transcription.segments
        if (segments.isNotEmpty()) {
            return buildString {
                var lastSpeaker: String? = null
                for (seg in segments) {
                    val speaker = seg.speakerId?.let { named[it] ?: "Speaker ${it.trimStart('S', 's')}" }
                    if (speaker != null && speaker != lastSpeaker) {
                        if (isNotEmpty()) appendLine()
                        append("**")
                        append(speaker)
                        append("**")
                        append(" · ")
                        append(formatTs(seg.startMs))
                        appendLine()
                        lastSpeaker = speaker
                    } else if (speaker == null && lastSpeaker != null) {
                        lastSpeaker = null
                    }
                    appendLine(seg.text.trim())
                }
            }.trim()
        }

        val text = transcription.text.trim()
        if (text.isEmpty()) return ""

        // Promote "Speaker N: …" lines to markdown bold speakers.
        return text.lineSequence().joinToString("\n") { line ->
            val match = SPEAKER_LINE.matchEntire(line.trim())
            if (match != null) {
                val name = match.groupValues[1].trim()
                val body = match.groupValues[2].trim()
                "**$name:** $body"
            } else {
                line
            }
        }
    }

    fun mergeSpeakerLabels(
        segments: List<TranscriptSegment>,
        diarization: DiarizationResult,
    ): List<TranscriptSegment> {
        if (segments.isEmpty() || diarization.spans.isEmpty()) return segments
        return segments.map { seg ->
            val mid = (seg.startMs + seg.endMs) / 2
            val span = diarization.spans.firstOrNull { mid in it.startMs..it.endMs }
            if (span == null) seg
            else seg.copy(speakerId = span.speakerId)
        }
    }

    private fun formatTs(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        fun two(n: Long) = if (n < 10) "0$n" else "$n"
        return if (h > 0) "$h:${two(m)}:${two(s)}" else "$m:${two(s)}"
    }

    private val SPEAKER_LINE = Regex("""^(Speaker\s+\d+|Orador\s+\d+|[\w .'-]+):\s*(.*)$""")
}
