package dev.velum.ai.local.asr

import dev.velum.ai.engine.DiarizationResult
import dev.velum.ai.engine.LlmRequest
import dev.velum.ai.engine.LlmResult
import dev.velum.ai.engine.TranscriptionResult
import dev.velum.ai.local.VoiceProfileStore

/**
 * Platform ASR / diarization / local LLM bridge.
 * Android wires sherpa-onnx OfflineRecognizer + LiteRT-LM; JVM/iOS fail loudly.
 */
expect class LocalAsrRuntime() {
    suspend fun transcribe(
        pcmOrEncoded: ByteArray,
        sampleRateHz: Int,
        languageHint: String?,
        modelId: String,
        modelPath: String,
    ): TranscriptionResult

    suspend fun diarize(
        pcmOrEncoded: ByteArray,
        sampleRateHz: Int,
        modelId: String,
        modelPath: String,
        embeddingModelPath: String,
        voiceProfiles: VoiceProfileStore?,
        recordingId: String?,
    ): DiarizationResult

    suspend fun complete(
        request: LlmRequest,
        modelId: String,
        modelPath: String,
    ): LlmResult
}
