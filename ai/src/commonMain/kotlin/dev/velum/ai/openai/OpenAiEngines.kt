package dev.velum.ai.openai

import dev.velum.ai.ChatMessage
import dev.velum.ai.MissingApiKeyException
import dev.velum.ai.OpenAiCompatibleClient
import dev.velum.ai.engine.AudioInput
import dev.velum.ai.engine.DiarizationResult
import dev.velum.ai.engine.Diarizer
import dev.velum.ai.engine.LlmEngine
import dev.velum.ai.engine.LlmRequest
import dev.velum.ai.engine.LlmResult
import dev.velum.ai.engine.Transcriber
import dev.velum.ai.engine.TranscriptionResult
import dev.velum.core.settings.SettingsRepository

class OpenAiTranscriber(
    private val client: OpenAiCompatibleClient,
    private val settings: SettingsRepository,
) : Transcriber {
    override val engineId: String = "openai-asr"

    override suspend fun transcribe(input: AudioInput, modelId: String): TranscriptionResult {
        val cfg = settings.settings.value
        val key = settings.getApiKey().orEmpty()
        if (!cfg.hasApiKey || key.isBlank()) {
            throw MissingApiKeyException(
                "API key required for API transcription. Add your OpenAI-compatible key in Account settings.",
            )
        }
        val model = modelId.ifBlank { cfg.transcriptionModel ?: "whisper-1" }
        val lang = input.languageHint?.takeIf { it != "auto" }
        val result = client.transcribe(
            baseUrl = cfg.baseUrl,
            apiKey = key,
            model = model,
            filename = input.filename,
            audioBytes = input.bytes,
            language = lang,
        )
        return TranscriptionResult(
            text = result.text,
            engineId = engineId,
            modelId = model,
            detectedLanguage = lang,
        )
    }
}

/**
 * No standard OpenAI-compatible diarization contract yet.
 * Local diarization is required — do not fake an empty success.
 */
class OpenAiDiarizer : Diarizer {
    override val engineId: String = "openai-diarizer"

    override suspend fun diarize(input: AudioInput, modelId: String): DiarizationResult {
        throw UnsupportedOperationException(
            "API diarization has no remote contract yet. " +
                "Switch Speaker diarization to Local and download pyannote + an embedding model " +
                "(WeSpeaker / TitaNet) in Model manager.",
        )
    }
}

class OpenAiLlmEngine(
    private val client: OpenAiCompatibleClient,
    private val settings: SettingsRepository,
) : LlmEngine {
    override val engineId: String = "openai-llm"

    override suspend fun complete(request: LlmRequest, modelId: String): LlmResult {
        val cfg = settings.settings.value
        val key = settings.getApiKey().orEmpty()
        if (!cfg.hasApiKey || key.isBlank()) {
            throw MissingApiKeyException(
                "API key required for API summaries. Add your OpenAI-compatible key in Account settings.",
            )
        }
        val model = modelId.ifBlank { cfg.chatModel ?: "gpt-4o-mini" }
        val system = buildString {
            append(request.systemPrompt)
            if (request.outputLanguage != "auto") {
                append("\nRespond in language: ${request.outputLanguage}.")
            }
        }
        val text = client.chatCompletion(
            baseUrl = cfg.baseUrl,
            apiKey = key,
            model = model,
            messages = listOf(
                ChatMessage(role = "system", content = system),
                ChatMessage(role = "user", content = request.userPrompt),
            ),
        )
        return LlmResult(
            text = text,
            engineId = engineId,
            modelId = model,
        )
    }
}
