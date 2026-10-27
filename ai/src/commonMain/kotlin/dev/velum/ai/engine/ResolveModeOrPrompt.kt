package dev.velum.ai.engine

import dev.velum.ai.local.AsrSizeTier
import dev.velum.ai.local.LocalAsrRouter
import dev.velum.ai.local.LocalModelManager
import dev.velum.ai.local.detectDeviceComputeClass
import dev.velum.core.model.AiProviderSettings
import dev.velum.core.model.ComputeMode

/** Outcome of checking whether Local is ready for a task, else prompt the user. */
sealed class ModeResolution {
    data class Proceed(val mode: ComputeMode) : ModeResolution()
    data class NeedLocalModel(
        val task: AiTask,
        val modelId: String,
        val displayName: String,
    ) : ModeResolution()
}

const val DEFAULT_LOCAL_ASR_MODEL_ID = "parakeet-tdt-0.6b-v3-int8"
const val DEFAULT_LOCAL_DIARIZATION_MODEL_ID = "pyannote-segmentation-3.0"
const val DEFAULT_LOCAL_EMBEDDING_MODEL_ID = "3dspeaker-eres2net-base"
const val DEFAULT_LOCAL_LLM_MODEL_ID = "gemma-4-e4b"

fun requiredLocalModelId(task: AiTask, settings: AiProviderSettings): String = when (task) {
    AiTask.Transcription ->
        settings.localAsrModelId?.takeIf { it.isNotBlank() }
            ?: LocalAsrRouter.chooseModelId(
                languageHint = settings.audioLanguage,
                tier = parseAsrTier(settings.asrSizeTier),
                deviceClass = detectDeviceComputeClass(),
            )
    AiTask.Diarization ->
        settings.localDiarizationModelId?.takeIf { it.isNotBlank() }
            ?: DEFAULT_LOCAL_DIARIZATION_MODEL_ID
    AiTask.Summary ->
        settings.localLlmModelId?.takeIf { it.isNotBlank() }
            ?: DEFAULT_LOCAL_LLM_MODEL_ID
}

fun parseAsrTier(raw: String?): AsrSizeTier = when (raw?.lowercase()) {
    "tiny" -> AsrSizeTier.Tiny
    "standard" -> AsrSizeTier.Standard
    "quality" -> AsrSizeTier.Quality
    else -> AsrSizeTier.Auto
}

/**
 * Default is [ComputeMode.Local]. If Local is selected but the required model is not Ready,
 * return [ModeResolution.NeedLocalModel] so UI can offer Download or switch to API —
 * never silently fail.
 */
fun resolveModeOrPrompt(
    task: AiTask,
    settings: AiProviderSettings,
    modelManager: LocalModelManager,
): ModeResolution {
    val mode = when (task) {
        AiTask.Transcription -> settings.transcriptionMode
        AiTask.Diarization -> settings.diarizationMode
        AiTask.Summary -> settings.summaryMode
    }
    if (mode == ComputeMode.Api) return ModeResolution.Proceed(ComputeMode.Api)

    val modelId = requiredLocalModelId(task, settings)
    if (!modelManager.isReady(modelId)) {
        val name = modelManager.stateFor(modelId)?.spec?.displayName ?: modelId
        return ModeResolution.NeedLocalModel(task, modelId, name)
    }
    if (task == AiTask.Diarization) {
        val embedReady = listOf(
            DEFAULT_LOCAL_EMBEDDING_MODEL_ID,
            "wespeaker-voxceleb",
            "titanet-small",
        ).any { modelManager.isReady(it) }
        if (!embedReady) {
            val embedId = DEFAULT_LOCAL_EMBEDDING_MODEL_ID
            val name = modelManager.stateFor(embedId)?.spec?.displayName ?: embedId
            return ModeResolution.NeedLocalModel(task, embedId, name)
        }
    }
    return ModeResolution.Proceed(ComputeMode.Local)
}
