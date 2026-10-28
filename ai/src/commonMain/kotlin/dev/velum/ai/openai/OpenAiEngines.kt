package dev.velum.ai.openai

import dev.velum.ai.ChatMessage
import dev.velum.ai.MissingApiKeyException
import dev.velum.ai.OpenAiCompatibleClient
import dev.velum.ai.audio.PcmWav
import dev.velum.ai.engine.AudioInput
import dev.velum.ai.engine.DiarizationResult
import dev.velum.ai.engine.Diarizer
import dev.velum.ai.engine.LlmEngine
import dev.velum.ai.engine.LlmRequest
import dev.velum.ai.engine.LlmResult
import dev.velum.ai.engine.Transcriber
import dev.velum.ai.engine.TranscriptionResult
import dev.velum.ai.pipeline.SummaryLocalization
import dev.velum.core.settings.AiApiTask
import dev.velum.core.settings.ApiModelOverride
import dev.velum.core.settings.SettingsRepository

class OpenAiTranscriber(
    private val client: OpenAiCompatibleClient,
    private val settings: SettingsRepository,
) : Transcriber {
    override val engineId: String = "openai-asr"

    override suspend fun transcribe(input: AudioInput, modelId: String): TranscriptionResult {
        // Prefer explicit AutoFlow/UI override (profile + model); fill blank model from modelId.
        val override = when {
            input.apiOverride != null && modelId.isNotBlank() ->
                input.apiOverride.copy(
                    modelId = input.apiOverride.modelId?.takeIf { it.isNotBlank() } ?: modelId,
                )
            input.apiOverride != null -> input.apiOverride
            modelId.isNotBlank() -> ApiModelOverride(modelId = modelId)
            else -> null
        }
        val resolved = settings.resolveApiModel(AiApiTask.Transcription, override)
        val key = settings.getProfileApiKey(resolved.profileId).orEmpty()
        if (key.isBlank()) {
            throw MissingApiKeyException(
                "API key required for API transcription. Add your OpenAI-compatible key in Settings → AI provider.",
            )
        }
        val model = resolved.modelId.ifBlank { "whisper-1" }
        val lang = input.languageHint?.takeIf { it != "auto" }
        val (filename, audioBytes) = prepareUpload(input)
        val result = client.transcribe(
            baseUrl = resolved.baseUrl,
            apiKey = key,
            model = model,
            filename = filename,
            audioBytes = audioBytes,
            language = lang,
            prompt = input.initialPrompt,
        )
        return TranscriptionResult(
            text = result.text,
            engineId = engineId,
            modelId = model,
            detectedLanguage = lang,
        )
    }

    /**
     * RecordingProcessor always feeds PCM after decode. Wrap as WAV unless the
     * payload is already a recognized audio container.
     */
    internal fun prepareUpload(input: AudioInput): Pair<String, ByteArray> {
        if (PcmWav.looksLikeWav(input.bytes) || PcmWav.looksLikeOgg(input.bytes)) {
            return input.filename to input.bytes
        }
        val wavName = input.filename
            .substringBeforeLast('.')
            .ifBlank { "audio" } + ".wav"
        return wavName to PcmWav.encode(input.bytes, input.sampleRateHz.coerceAtLeast(8_000))
    }
}

/**
 * No standard OpenAI-compatible diarization contract yet.
 * Local diarization is required — do not fake an empty success.
 * A model binding may still be configured for a future remote contract.
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
        val override = when {
            request.apiOverride != null && modelId.isNotBlank() ->
                request.apiOverride.copy(
                    modelId = request.apiOverride.modelId?.takeIf { it.isNotBlank() } ?: modelId,
                )
            request.apiOverride != null -> request.apiOverride
            modelId.isNotBlank() -> ApiModelOverride(modelId = modelId)
            else -> null
        }
        val resolved = settings.resolveApiModel(request.apiTask, override)
        val key = settings.getProfileApiKey(resolved.profileId).orEmpty()
        if (key.isBlank()) {
            throw MissingApiKeyException(
                "API key required for API summaries. Add your OpenAI-compatible key in Settings → AI provider.",
            )
        }
        val model = resolved.modelId.ifBlank { "gpt-4o-mini" }
        val system = buildString {
            append(request.systemPrompt)
            val lang = request.outputLanguage.trim().lowercase()
            if (lang.isNotEmpty() && lang != "auto") {
                append('\n')
                append(SummaryLocalization.languageInstruction(lang))
            }
        }
        val text = client.chatCompletion(
            baseUrl = resolved.baseUrl,
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
            warning = resolved.fallbackMessage,
        )
    }
}
