package dev.velum.ai.local.asr

import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import dev.velum.ai.engine.DiarizationResult
import dev.velum.ai.engine.LlmRequest
import dev.velum.ai.engine.LlmResult
import dev.velum.ai.engine.TranscriptionResult
import java.io.File

/**
 * Android sherpa-onnx bridge skeleton.
 *
 * The JitPack AAR is on the classpath. Full OfflineRecognizer config mapping
 * (transducer / whisper / sense-voice file layout after .tar.bz2 extract) is
 * intentionally incomplete — we fail loudly instead of returning fake text.
 */
actual class LocalAsrRuntime actual constructor() {
    actual suspend fun transcribe(
        pcmOrEncoded: ByteArray,
        sampleRateHz: Int,
        languageHint: String?,
        modelId: String,
        modelPath: String,
    ): TranscriptionResult {
        val artifact = File(modelPath)
        if (!artifact.exists()) {
            throw IllegalStateException("Model artifact missing at $modelPath")
        }
        if (artifact.name.endsWith(".tar.bz2") || artifact.name.endsWith(".bz2")) {
            throw IllegalStateException(
                "Model archive at $modelPath is not extracted. " +
                    "Extract sherpa-onnx weights (encoder/decoder/tokens) before Local ASR.",
            )
        }
        // Touch the API surface so the dependency stays wired; do not invent success text.
        val configClass = OfflineRecognizerConfig::class.java
        val recognizerClass = OfflineRecognizer::class.java
        throw UnsupportedOperationException(
            "Sherpa OfflineRecognizer is linked (${recognizerClass.name}, config=${configClass.name}) " +
                "but OfflineRecognizerConfig mapping for modelId=$modelId under $modelPath " +
                "is not finished. Refuse to return placeholder transcript.",
        )
    }

    actual suspend fun diarize(
        pcmOrEncoded: ByteArray,
        sampleRateHz: Int,
        modelId: String,
        modelPath: String,
    ): DiarizationResult {
        throw UnsupportedOperationException(
            "Local diarization OfflineSpeakerDiarization not wired yet " +
                "(modelId=$modelId path=$modelPath).",
        )
    }

    actual suspend fun complete(
        request: LlmRequest,
        modelId: String,
        modelPath: String,
    ): LlmResult {
        throw UnsupportedOperationException(
            "Local LLM (LiteRT-LM / llama.cpp) not wired on Android yet. modelId=$modelId",
        )
    }
}
