package dev.velum.ai

import dev.velum.ai.audio.PcmWav
import dev.velum.core.model.AiModelInfo
import dev.velum.core.settings.AiBaseUrl
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class OpenAiCompatibleClient(
    private val httpClient: HttpClient,
    private val json: Json = defaultJson,
) {
    suspend fun listModels(baseUrl: String, apiKey: String): List<AiModelInfo> {
        val url = joinUrl(baseUrl, "models")
        val response = httpClient.get(url) {
            bearerAuth(apiKey)
        }
        if (!response.status.isSuccess()) {
            throw AiApiException(
                status = response.status.value,
                message = ApiErrorSanitizer.httpFailure("GET /models", response.status.value),
            )
        }
        val body = response.body<ModelsResponse>()
        return body.data.map { AiModelInfo(id = it.id, ownedBy = it.ownedBy) }
            .sortedBy { it.id }
    }

    suspend fun transcribe(
        baseUrl: String,
        apiKey: String,
        model: String,
        filename: String,
        audioBytes: ByteArray,
        language: String? = null,
        prompt: String? = null,
    ): TranscriptionResult {
        val url = joinUrl(baseUrl, "audio/transcriptions")
        val response = httpClient.post(url) {
            bearerAuth(apiKey)
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("model", model)
                        if (language != null) append("language", language)
                        if (!prompt.isNullOrBlank()) append("prompt", prompt)
                        append(
                            "file",
                            audioBytes,
                            Headers.build {
                                append(
                                    HttpHeaders.ContentType,
                                    PcmWav.contentTypeForFilename(filename),
                                )
                                append(
                                    HttpHeaders.ContentDisposition,
                                    "filename=\"$filename\"",
                                )
                            },
                        )
                    },
                ),
            )
        }
        if (!response.status.isSuccess()) {
            throw AiApiException(
                status = response.status.value,
                message = ApiErrorSanitizer.httpFailure("POST /audio/transcriptions", response.status.value),
            )
        }
        val body = response.body<TranscriptionResponse>()
        return TranscriptionResult(text = body.text)
    }

    suspend fun chatCompletion(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        temperature: Double? = 0.3,
    ): String {
        val url = joinUrl(baseUrl, "chat/completions")
        suspend fun post(temperature: Double?): HttpResponse = httpClient.post(url) {
            bearerAuth(apiKey)
            contentType(ContentType.Application.Json)
            setBody(
                ChatCompletionRequest(
                    model = model,
                    messages = messages,
                    temperature = temperature,
                ),
            )
        }
        var response = post(temperature)
        // Reasoning models (OpenAI o-series, gpt-5, ...) accept only their default temperature and answer 400
        // "Unsupported value: 'temperature'". Ask once more without it instead of failing every summary.
        if (temperature != null && response.status == HttpStatusCode.BadRequest && mentionsTemperature(response)) {
            response = post(null)
        }
        if (!response.status.isSuccess()) {
            throw AiApiException(
                status = response.status.value,
                message = ApiErrorSanitizer.httpFailure("POST /chat/completions", response.status.value),
            )
        }
        val body = response.body<ChatCompletionResponse>()
        // content is null for refusals and for some reasoning models; an empty note must not be saved as a summary.
        val content = body.choices.firstOrNull()?.message?.content
        if (content.isNullOrBlank()) throw AiApiException(response.status.value, EMPTY_CHAT_COMPLETION_MESSAGE)
        return content
    }

    /** Only checks the error body for the parameter name; the body itself is never surfaced or logged. */
    private suspend fun mentionsTemperature(response: HttpResponse): Boolean =
        runCatching { response.bodyAsText() }.getOrDefault("").contains("temperature", ignoreCase = true)

    companion object {
        val defaultJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
        }

        fun joinUrl(baseUrl: String, path: String): String {
            val base = AiBaseUrl.normalizeAndValidate(baseUrl)
            val p = path.trimStart('/')
            return "$base/$p"
        }
    }
}

class AiApiException(val status: Int, override val message: String) : Exception(message)

/** Matches the UI's "model produced no text" mapping, so the failure is shown localized. */
const val EMPTY_CHAT_COMPLETION_MESSAGE = "API chat model returned empty text"

/** Thrown when an API-mode task runs without a configured key. */
class MissingApiKeyException(
    override val message: String =
        "API key required. Add your OpenAI-compatible key in Settings → AI provider.",
) : IllegalStateException(message)

@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
)

@Serializable
data class TranscriptionResult(val text: String)

@Serializable
private data class ModelsResponse(val data: List<ModelDto> = emptyList())

@Serializable
private data class ModelDto(
    val id: String,
    @SerialName("owned_by") val ownedBy: String? = null,
)

@Serializable
private data class TranscriptionResponse(val text: String = "")

@Serializable
private data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    /** Omitted when null (explicitNulls = false): the provider's default applies. */
    val temperature: Double? = null,
)

@Serializable
private data class ChatCompletionResponse(
    val choices: List<Choice> = emptyList(),
) {
    @Serializable
    data class Choice(val message: ResponseMessage? = null)

    @Serializable
    data class ResponseMessage(val role: String? = null, val content: String? = null)
}
