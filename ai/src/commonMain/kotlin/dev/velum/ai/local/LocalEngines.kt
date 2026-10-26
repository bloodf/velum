package dev.velum.ai.local

import dev.velum.ai.engine.AudioInput
import dev.velum.ai.engine.DiarizationResult
import dev.velum.ai.engine.Diarizer
import dev.velum.ai.engine.LlmEngine
import dev.velum.ai.engine.LlmRequest
import dev.velum.ai.engine.LlmResult
import dev.velum.ai.engine.Transcriber
import dev.velum.ai.engine.TranscriptionResult

/**
 * On-device ASR stub. Real path: decode → VAD → [LocalAsrRouter] → sherpa-onnx.
 */
class LocalTranscriber(
    private val modelManager: LocalModelManager = LocalModelManager(),
) : Transcriber {
    override val engineId: String = "local-asr"

    override suspend fun transcribe(input: AudioInput, modelId: String): TranscriptionResult {
        val resolved = modelId.ifBlank {
            LocalAsrRouter.chooseModelId(input.languageHint)
        }
        val state = modelManager.stateFor(resolved)
        if (state?.status != LocalModelStatus.Ready) {
            // Stub still returns text so UI can be exercised without downloads.
        }
        return TranscriptionResult(
            text = "[local/$resolved] Transcription pending native sherpa-onnx wiring.",
            detectedLanguage = input.languageHint?.takeIf { it != "auto" },
            engineId = engineId,
            modelId = resolved,
        )
    }
}

/**
 * On-device diarization stub: pyannote-3.0 + multilingual embedding + voice profiles.
 */
class LocalDiarizer : Diarizer {
    override val engineId: String = "local-diarizer"

    override suspend fun diarize(input: AudioInput, modelId: String): DiarizationResult {
        val resolved = modelId.ifBlank { "pyannote-segmentation-3.0" }
        return DiarizationResult(
            spans = emptyList(),
            namedSpeakers = emptyMap(),
            engineId = engineId,
            modelId = resolved,
        )
    }
}

/**
 * On-device LLM stub: Gemma 4 E4B/E2B (LiteRT-LM) or Qwen3-4B (llama.cpp) with map-reduce.
 */
class LocalLlmEngine : LlmEngine {
    override val engineId: String = "local-llm"

    override suspend fun complete(request: LlmRequest, modelId: String): LlmResult {
        val resolved = modelId.ifBlank { "gemma-4-e4b" }
        val needsMapReduce = request.userPrompt.length > 24_000
        return LlmResult(
            text = "[local/$resolved] Summary pending LiteRT-LM / llama.cpp wiring.",
            engineId = engineId,
            modelId = resolved,
            usedMapReduce = needsMapReduce,
        )
    }
}
