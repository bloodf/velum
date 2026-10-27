package dev.velum.ai.local.asr

import dev.velum.ai.engine.DiarizationResult
import dev.velum.ai.engine.LlmRequest
import dev.velum.ai.engine.LlmResult
import dev.velum.ai.engine.TranscriptionResult
import dev.velum.ai.local.VoiceProfileStore

actual class LocalAsrRuntime actual constructor() {
    actual suspend fun transcribe(
        pcmOrEncoded: ByteArray,
        sampleRateHz: Int,
        languageHint: String?,
        modelId: String,
        modelPath: String,
        initialPrompt: String?,
    ): TranscriptionResult {
        throw UnsupportedOperationException(
            "Local ASR is Android-first; iOS on-device runtime not wired yet. modelId=$modelId",
        )
    }

    actual suspend fun diarize(
        pcmOrEncoded: ByteArray,
        sampleRateHz: Int,
        modelId: String,
        modelPath: String,
        embeddingModelPath: String,
        voiceProfiles: VoiceProfileStore?,
        recordingId: String?,
        matchThreshold: Float,
    ): DiarizationResult {
        throw UnsupportedOperationException(
            "Local diarization is Android-first; iOS not wired yet. modelId=$modelId",
        )
    }

    actual suspend fun complete(
        request: LlmRequest,
        modelId: String,
        modelPath: String,
    ): LlmResult {
        throw UnsupportedOperationException(
            "Local LLM is Android-first; iOS not wired yet. modelId=$modelId",
        )
    }
}
