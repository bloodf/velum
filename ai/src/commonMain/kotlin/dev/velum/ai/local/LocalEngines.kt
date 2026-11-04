package dev.velum.ai.local

import dev.velum.ai.engine.AudioInput
import dev.velum.ai.engine.DiarizationResult
import dev.velum.ai.engine.Diarizer
import dev.velum.ai.engine.LlmEngine
import dev.velum.ai.engine.LlmRequest
import dev.velum.ai.engine.LlmResult
import dev.velum.ai.engine.Transcriber
import dev.velum.ai.engine.TranscriptionResult
import dev.velum.ai.engine.resolveLocalLlmModelId
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
            initialPrompt = input.initialPrompt,
        )
    }

    private fun ensureReady(modelId: String) {
        val state = modelManager.stateFor(modelId)
        if (state?.status != LocalModelStatus.Ready) {
            throw ModelNotReadyException(modelId)
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
    private val matchThreshold: () -> Float = { VoiceProfileStore.DEFAULT_MATCH_THRESHOLD },
) : Diarizer {
    override val engineId: String = "local-diarizer"

    /** Mirrors the gate in [diarize]: without the models it never runs, so there is nothing to overwrite. */
    override suspend fun speakerNamingReady(modelId: String): Boolean {
        val resolved = modelId.ifBlank { "pyannote-segmentation-3.0" }
        val wouldRun = modelManager.stateFor(resolved)?.status == LocalModelStatus.Ready &&
            modelManager.modelPath(resolved) != null &&
            chooseReadyEmbeddingId()?.let { modelManager.modelPath(it) } != null
        return !wouldRun || voiceProfiles.speakerNamingReady()
    }

    override suspend fun diarize(input: AudioInput, modelId: String): DiarizationResult {
        val resolved = modelId.ifBlank { "pyannote-segmentation-3.0" }
        val state = modelManager.stateFor(resolved)
        if (state?.status != LocalModelStatus.Ready) {
            throw ModelNotReadyException(resolved)
        }
        val embeddingId = chooseReadyEmbeddingId()
            ?: throw ModelNotReadyException("3dspeaker-eres2net-base")
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
            matchThreshold = matchThreshold(),
        )
    }

    private fun chooseReadyEmbeddingId(): String? {
        // Prefer 3D-Speaker ERes2Net (multilingual training) over VoxCeleb-only WeSpeaker.
        val candidates = listOf(
            "3dspeaker-eres2net-base",
            "wespeaker-voxceleb",
            "titanet-small",
        )
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
        val resolved = resolveLocalLlmModelId(modelId, modelManager)
        val state = modelManager.stateFor(resolved)
        if (state?.status != LocalModelStatus.Ready) {
            throw ModelNotReadyException(resolved)
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
