package dev.velum.ai.engine

import dev.velum.core.model.AiProviderSettings
import dev.velum.core.model.ComputeMode
import kotlinx.coroutines.flow.StateFlow

/**
 * Resolves Local vs API engines per task. Mixing across tasks is allowed.
 */
class AiTaskRouter(
    private val settings: StateFlow<AiProviderSettings>,
    private val localTranscriber: Transcriber,
    private val apiTranscriber: Transcriber,
    private val localDiarizer: Diarizer,
    private val apiDiarizer: Diarizer,
    private val localLlm: LlmEngine,
    private val apiLlm: LlmEngine,
) {
    fun transcriber(): Transcriber =
        if (settings.value.transcriptionMode == ComputeMode.Api) apiTranscriber else localTranscriber

    fun diarizer(): Diarizer =
        if (settings.value.diarizationMode == ComputeMode.Api) apiDiarizer else localDiarizer

    fun llm(): LlmEngine =
        if (settings.value.summaryMode == ComputeMode.Api) apiLlm else localLlm

    fun modeFor(task: AiTask): ComputeMode = when (task) {
        AiTask.Transcription -> settings.value.transcriptionMode
        AiTask.Diarization -> settings.value.diarizationMode
        AiTask.Summary -> settings.value.summaryMode
    }
}
