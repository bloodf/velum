package dev.velum.ai.engine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

enum class AiTask {
    Transcription,
    Diarization,
    Summary,
}

data class AudioInput(
    val filename: String,
    val bytes: ByteArray,
    val sampleRateHz: Int = 16_000,
    /** BCP-47 / ISO code, or "auto". */
    val languageHint: String? = "auto",
)

data class TranscriptSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val speakerId: String? = null,
    val language: String? = null,
)

data class TranscriptionResult(
    val text: String,
    val segments: List<TranscriptSegment> = emptyList(),
    val detectedLanguage: String? = null,
    val engineId: String,
    val modelId: String,
)

data class SpeakerSpan(
    val speakerId: String,
    val startMs: Long,
    val endMs: Long,
)

data class DiarizationResult(
    val spans: List<SpeakerSpan>,
    /** speakerId → display name when matched to a saved voice profile. */
    val namedSpeakers: Map<String, String> = emptyMap(),
    val engineId: String,
    val modelId: String,
)

data class LlmRequest(
    val systemPrompt: String,
    val userPrompt: String,
    /** Output language: "auto" (recording language) or a BCP-47 code. */
    val outputLanguage: String = "auto",
    val maxOutputTokens: Int = 1024,
)

data class LlmResult(
    val text: String,
    val engineId: String,
    val modelId: String,
    val usedMapReduce: Boolean = false,
)

interface Transcriber {
    val engineId: String
    suspend fun transcribe(input: AudioInput, modelId: String): TranscriptionResult
}

interface Diarizer {
    val engineId: String
    suspend fun diarize(input: AudioInput, modelId: String): DiarizationResult
}

interface LlmEngine {
    val engineId: String
    suspend fun complete(request: LlmRequest, modelId: String): LlmResult
}

/** Progress for long on-device jobs (WorkManager will drive this later). */
sealed class JobProgress {
    data class Running(val fraction: Float, val message: String) : JobProgress()
    data class Succeeded(val message: String = "done") : JobProgress()
    data class Failed(val message: String) : JobProgress()
    data class PausedThermal(val message: String = "Waiting for device to cool") : JobProgress()
}

fun Flow<JobProgress>.asStubSuccess(message: String = "Stub complete"): Flow<JobProgress> = flow {
    emit(JobProgress.Running(0.1f, "Starting"))
    emit(JobProgress.Running(0.6f, "Working"))
    emit(JobProgress.Succeeded(message))
}
