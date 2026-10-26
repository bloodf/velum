package dev.velum.ai.local.asr

import dev.velum.ai.engine.DiarizationResult
import dev.velum.ai.engine.LlmRequest
import dev.velum.ai.engine.LlmResult
import dev.velum.ai.engine.TranscriptionResult

actual class LocalAsrRuntime actual constructor() {
    actual suspend fun transcribe(
        pcmOrEncoded: ByteArray,
        sampleRateHz: Int,
        languageHint: String?,
        modelId: String,
        modelPath: String,
    ): TranscriptionResult {
        throw UnsupportedOperationException(
            "Local ASR (sherpa-onnx) is not supported on JVM desktop yet. " +
                "Use API transcription, or run on Android. modelId=$modelId path=$modelPath",
        )
    }

    actual suspend fun diarize(
        pcmOrEncoded: ByteArray,
        sampleRateHz: Int,
        modelId: String,
        modelPath: String,
    ): DiarizationResult {
        throw UnsupportedOperationException(
            "Local diarization is not supported on JVM desktop yet. modelId=$modelId",
        )
    }

    actual suspend fun complete(
        request: LlmRequest,
        modelId: String,
        modelPath: String,
    ): LlmResult {
        throw UnsupportedOperationException(
            "Local LLM (LiteRT-LM / llama.cpp) is not supported on JVM desktop yet. " +
                "Use API summaries. modelId=$modelId",
        )
    }
}
