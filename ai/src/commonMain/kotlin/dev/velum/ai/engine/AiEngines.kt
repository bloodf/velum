package dev.velum.ai.engine

import dev.velum.core.settings.AiApiTask
import dev.velum.core.settings.ApiModelOverride
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
    /** Optional recording id for voice-profile cluster cache. */
    val recordingId: String? = null,
    /** Whisper-style initial_prompt (glossary / vocabulary hints). */
    val initialPrompt: String? = null,
    /** AutoFlow / UI profile+model override for API ASR (mirrors [LlmRequest.apiOverride]). */
    val apiOverride: ApiModelOverride? = null,
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
    /** Which API task binding to resolve when using an OpenAI-compatible engine. */
    val apiTask: AiApiTask = AiApiTask.Summary,
    val apiOverride: ApiModelOverride? = null,
)

data class LlmResult(
    val text: String,
    val engineId: String,
    val modelId: String,
    val usedMapReduce: Boolean = false,
    /** Soft warning when the resolver fell back (never contains API keys). */
    val warning: String? = null,
)

interface Transcriber {
    val engineId: String
    suspend fun transcribe(input: AudioInput, modelId: String): TranscriptionResult
}

/**
 * Streaming local transcription. Emits partial Markdown as PCM arrives.
 * Production Android uses a real sherpa path; JVM ships a test double only.
 */
interface StreamingTranscriber {
    val engineId: String
    fun start(
        sampleRateHz: Int,
        languageHint: String? = "auto",
        initialPrompt: String? = null,
    )
    /** Push interleaved PCM16 LE. May emit partial Markdown via [onPartial]. */
    fun pushPcm(pcm16Le: ByteArray)
    /** Flush remaining audio and emit a final Markdown transcript. */
    fun finish(): String
    var onPartial: (markdown: String) -> Unit
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
