package dev.velum.ai.local

import dev.velum.ai.engine.AudioInput
import dev.velum.ai.engine.DiarizationResult
import dev.velum.ai.engine.Diarizer
import dev.velum.ai.engine.LlmEngine
import dev.velum.ai.engine.LlmRequest
import dev.velum.ai.engine.LlmResult
import dev.velum.ai.engine.Transcriber
import dev.velum.ai.engine.TranscriptionResult
import dev.velum.ai.local.asr.LocalAsrRuntime

/**
 * On-device ASR. Requires a Ready model; never returns placeholder text.
 */
class LocalTranscriber(
    private val modelManager: LocalModelManager,
    private val runtime: LocalAsrRuntime = LocalAsrRuntime(),
) : Transcriber {
    override val engineId: String = "local-asr"

    override suspend fun transcribe(input: AudioInput, modelId: String): TranscriptionResult {
        val resolved = modelId.ifBlank {
            LocalAsrRouter.chooseModelId(input.languageHint)
        }
        ensureReady(resolved)
        val modelPath = modelManager.modelPath(resolved)
            ?: throw ModelNotReadyException(resolved)
        return runtime.transcribe(
            pcmOrEncoded = input.bytes,
            sampleRateHz = input.sampleRateHz,
            languageHint = input.languageHint,
            modelId = resolved,
            modelPath = modelPath.absolutePath,
        )
    }

    private fun ensureReady(modelId: String) {
        val state = modelManager.stateFor(modelId)
        if (state?.status != LocalModelStatus.Ready) {
            throw ModelNotReadyException(
                modelId,
                "Local ASR model '$modelId' is not Ready (status=${state?.status}). " +
                    "Download it in Model manager before using Local transcription.",
            )
        }
    }
}

/**
 * On-device diarization via sherpa-onnx OfflineSpeakerDiarization + voice profile match.
 */
class LocalDiarizer(
    private val modelManager: LocalModelManager,
    private val voiceProfiles: VoiceProfileStore,
    private val runtime: LocalAsrRuntime = LocalAsrRuntime(),
) : Diarizer {
    override val engineId: String = "local-diarizer"

    override suspend fun diarize(input: AudioInput, modelId: String): DiarizationResult {
        val resolved = modelId.ifBlank { "pyannote-segmentation-3.0" }
        val state = modelManager.stateFor(resolved)
        if (state?.status != LocalModelStatus.Ready) {
            throw ModelNotReadyException(
                resolved,
                "Local diarization model '$resolved' is not Ready (status=${state?.status}). " +
                    "Download it in Model manager, or switch Diarization to API.",
            )
        }
        val embeddingId = chooseReadyEmbeddingId()
            ?: throw ModelNotReadyException(
                "wespeaker-voxceleb",
                "Local speaker embedding model is not Ready. " +
                    "Download WeSpeaker or TitaNet in Model manager for diarization.",
            )
        val modelPath = modelManager.modelPath(resolved)
            ?: throw ModelNotReadyException(resolved)
        val embeddingPath = modelManager.modelPath(embeddingId)
            ?: throw ModelNotReadyException(embeddingId)
        return runtime.diarize(
            pcmOrEncoded = input.bytes,
            sampleRateHz = input.sampleRateHz,
            modelId = resolved,
            modelPath = modelPath.absolutePath,
            embeddingModelPath = embeddingPath.absolutePath,
            voiceProfiles = voiceProfiles,
            recordingId = input.recordingId,
        )
    }

    private fun chooseReadyEmbeddingId(): String? {
        val candidates = listOf("wespeaker-voxceleb", "titanet-small")
        return candidates.firstOrNull { modelManager.isReady(it) }
    }
}

/**
 * On-device LLM. Requires Ready weights; never returns placeholder summaries.
 */
class LocalLlmEngine(
    private val modelManager: LocalModelManager,
    private val runtime: LocalAsrRuntime = LocalAsrRuntime(),
) : LlmEngine {
    override val engineId: String = "local-llm"

    override suspend fun complete(request: LlmRequest, modelId: String): LlmResult {
        val resolved = modelId.ifBlank { "gemma-4-e4b" }
        val state = modelManager.stateFor(resolved)
        if (state?.status != LocalModelStatus.Ready) {
            throw ModelNotReadyException(
                resolved,
                "Local LLM '$resolved' is not Ready (status=${state?.status}). " +
                    "Download it in Model manager, or switch Summaries to API.",
            )
        }
        val modelPath = modelManager.modelPath(resolved)
            ?: throw ModelNotReadyException(resolved)
        return runtime.complete(
            request = request,
            modelId = resolved,
            modelPath = modelPath.absolutePath,
        )
    }
}
