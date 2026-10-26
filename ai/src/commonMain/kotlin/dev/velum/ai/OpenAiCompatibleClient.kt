package dev.velum.ai

import dev.velum.core.model.AiModelInfo
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
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
                message = "GET /models failed: ${response.status} ${response.bodyAsText().take(400)}",
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
    ): TranscriptionResult {
        val url = joinUrl(baseUrl, "audio/transcriptions")
        val response = httpClient.post(url) {
            bearerAuth(apiKey)
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("model", model)
                        if (language != null) append("language", language)
                        append(
                            "file",
                            audioBytes,
                            Headers.build {
                                append(HttpHeaders.ContentType, "audio/ogg")
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
                message = "POST /audio/transcriptions failed: ${response.status} ${response.bodyAsText().take(400)}",
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
        temperature: Double = 0.3,
    ): String {
        val url = joinUrl(baseUrl, "chat/completions")
        val response = httpClient.post(url) {
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
        if (!response.status.isSuccess()) {
            throw AiApiException(
                status = response.status.value,
                message = "POST /chat/completions failed: ${response.status} ${response.bodyAsText().take(400)}",
            )
        }
        val body = response.body<ChatCompletionResponse>()
        return body.choices.firstOrNull()?.message?.content
            ?: throw AiApiException(response.status.value, "Empty chat completion")
    }

    companion object {
        val defaultJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
        }

        fun joinUrl(baseUrl: String, path: String): String {
            val base = baseUrl.trim().trimEnd('/')
            val p = path.trimStart('/')
            return "$base/$p"
        }
    }
}

class AiApiException(val status: Int, override val message: String) : Exception(message)

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
    val temperature: Double = 0.3,
)

@Serializable
private data class ChatCompletionResponse(
    val choices: List<Choice> = emptyList(),
) {
    @Serializable
    data class Choice(val message: ChatMessage? = null)
}
