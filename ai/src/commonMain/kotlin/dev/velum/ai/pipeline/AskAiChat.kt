package dev.velum.ai.pipeline

import dev.velum.ai.MissingApiKeyException
import dev.velum.ai.engine.AiTaskRouter
import dev.velum.ai.engine.LlmRequest
import dev.velum.core.data.VelumRepository
import dev.velum.core.model.ComputeMode
import dev.velum.core.settings.SettingsRepository

/**
 * Local Ask AI over a recording's transcript + summary. No Plaud ask endpoints.
 */
class AskAiChat(
    private val aiRouter: AiTaskRouter,
    private val repository: VelumRepository,
    private val settings: SettingsRepository,
) {
    data class Turn(val role: String, val content: String)

    suspend fun ask(
        recordingId: String,
        question: String,
        history: List<Turn> = emptyList(),
    ): String {
        val recording = repository.getRecording(recordingId)
            ?: throw IllegalArgumentException("Recording not found: $recordingId")
        val cfg = settings.settings.value
        val prefs = settings.preferences.value
        ensureApiKeyIfNeeded(cfg.summaryMode)

        val personalization = repository.personalizationPromptFragment(
            includeMemory = prefs.useSavedMemory,
        )
        val system = buildString {
            append("You answer questions about one recording note. ")
            append("Use only the transcript and summary below. Reply in Markdown.\n")
            if (personalization.isNotBlank()) {
                append(personalization).append('\n')
            }
            if (prefs.customInstructions.isNotBlank()) {
                append("Custom instructions: ").append(prefs.customInstructions).append('\n')
            }
            append("\n--- Transcript ---\n")
            append(recording.transcript.orEmpty().ifBlank { "(empty)" })
            append("\n\n--- Summary ---\n")
            append(recording.summary_markdown.orEmpty().ifBlank { "(empty)" })
        }
        val historyBlock = history.joinToString("\n") { "${it.role}: ${it.content}" }
        val user = buildString {
            if (historyBlock.isNotBlank()) {
                append("Prior turns:\n").append(historyBlock).append("\n\n")
            }
            append(question.trim())
        }
        val llm = aiRouter.llm()
        val model = when (cfg.summaryMode) {
            ComputeMode.Local -> cfg.localLlmModelId.orEmpty()
            ComputeMode.Api -> cfg.chatModel.orEmpty()
        }
        return llm.complete(
            LlmRequest(
                systemPrompt = system,
                userPrompt = user,
                outputLanguage = cfg.outputLanguage,
            ),
            model,
        ).text
    }

    private suspend fun ensureApiKeyIfNeeded(mode: ComputeMode) {
        if (mode != ComputeMode.Api) return
        val key = settings.getApiKey()
        if (key.isNullOrBlank()) {
            throw MissingApiKeyException(
                "API key required for Ask AI. Add your OpenAI-compatible key in Settings → AI provider.",
            )
        }
    }
}
