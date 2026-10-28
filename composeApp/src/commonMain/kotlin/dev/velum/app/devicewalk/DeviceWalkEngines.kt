package dev.velum.app.devicewalk

import dev.velum.ai.engine.AudioInput
import dev.velum.ai.engine.DiarizationResult
import dev.velum.ai.engine.Diarizer
import dev.velum.ai.engine.LlmEngine
import dev.velum.ai.engine.LlmRequest
import dev.velum.ai.engine.LlmResult
import dev.velum.ai.engine.SpeakerSpan
import dev.velum.ai.engine.Transcriber
import dev.velum.ai.engine.TranscriptSegment
import dev.velum.ai.engine.TranscriptionResult

/**
 * Deterministic on-device stand-ins so the device walk can exercise transcription /
 * notes / Ask without downloading multi-hundred-MB models in CI.
 */
object DeviceWalkTranscriber : Transcriber {
    override val engineId: String = "device-walk-asr"

    override suspend fun transcribe(input: AudioInput, modelId: String): TranscriptionResult {
        val text =
            "Device walk transcript. Synthetic speech for CI coverage of notes and Ask."
        return TranscriptionResult(
            text = text,
            segments = listOf(
                TranscriptSegment(
                    startMs = 0,
                    endMs = 400,
                    text = text,
                    speakerId = "S1",
                ),
            ),
            detectedLanguage = "en",
            engineId = engineId,
            modelId = modelId.ifBlank { "device-walk-asr" },
        )
    }
}

object DeviceWalkDiarizer : Diarizer {
    override val engineId: String = "device-walk-diar"

    override suspend fun diarize(input: AudioInput, modelId: String): DiarizationResult =
        DiarizationResult(
            spans = listOf(SpeakerSpan(speakerId = "S1", startMs = 0, endMs = 400)),
            namedSpeakers = mapOf("S1" to "Speaker 1"),
            engineId = engineId,
            modelId = modelId.ifBlank { "device-walk-diar" },
        )
}

object DeviceWalkLlm : LlmEngine {
    override val engineId: String = "device-walk-llm"

    override suspend fun complete(request: LlmRequest, modelId: String): LlmResult {
        val headings = Regex("##\\s+([^\\n]+)")
            .findAll(request.systemPrompt)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotBlank() }
            .toList()
            .ifEmpty { listOf("Overview", "Key points", "Action items") }
        val md = buildString {
            headings.take(6).forEach { section ->
                append("## ").append(section).append('\n')
                append("- Device walk note derived from transcript\n\n")
            }
            if (request.userPrompt.contains("?", ignoreCase = true) ||
                request.userPrompt.contains("ask", ignoreCase = true)
            ) {
                append("Answer: Device walk can see the seeded transcript.\n")
            }
        }
        return LlmResult(
            text = md.trim(),
            engineId = engineId,
            modelId = modelId.ifBlank { "device-walk-llm" },
        )
    }
}
