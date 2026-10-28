package dev.velum.ai.integrations

import dev.velum.ai.createAiHttpClient
import dev.velum.core.data.VelumRepository
import dev.velum.core.integrations.WebhookPayload
import dev.velum.core.integrations.WebhookSettingsStore
import dev.velum.core.integrations.WebhookSigner
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay

/**
 * POSTs a versioned recording payload to the user-configured webhook URL.
 * Optional HMAC-SHA256 via [WebhookSigner.SIGNATURE_HEADER].
 */
class WebhookDispatcher(
    private val repository: VelumRepository,
    private val settings: WebhookSettingsStore,
    private val httpClient: HttpClient = createAiHttpClient(),
    private val maxAttempts: Int = 3,
) {
    /**
     * @return short success message, or null on failure (caller shows localized fallback).
     */
    suspend fun dispatch(recordingId: String): String? {
        val url = settings.getUrl() ?: return null
        val rec = repository.getRecording(recordingId) ?: return null
        val speakers = repository.getSpeakers(recordingId)
            .mapNotNull { it.display_name ?: it.label }
        val payload = WebhookPayload.fromRecording(
            recordingId = rec.id,
            title = rec.title,
            startedAt = rec.started_at,
            durationSeconds = rec.duration_seconds,
            source = rec.source,
            speakers = speakers,
            transcript = rec.transcript,
            summaryMarkdown = rec.summary_markdown,
        )
        return postEncoded(url, WebhookPayload.encode(payload))
    }

    /**
     * POST a minimal test payload so the user can verify URL + HMAC without a recording.
     * @return success detail (e.g. `HTTP 200`) or error detail; null if webhook not configured.
     */
    suspend fun ping(): String? {
        val url = settings.getUrl() ?: return null
        val payload = WebhookPayload.testPing()
        return postEncoded(url, WebhookPayload.encode(payload))
    }

    private suspend fun postEncoded(url: String, body: String): String? {
        val secret = settings.getSecret()
        var lastError: String? = null
        repeat(maxAttempts) { attempt ->
            try {
                val response = httpClient.post(url) {
                    contentType(ContentType.Application.Json)
                    if (!secret.isNullOrBlank()) {
                        header(
                            WebhookSigner.SIGNATURE_HEADER,
                            WebhookSigner.signatureHeaderValue(secret, body),
                        )
                    }
                    setBody(body)
                }
                if (response.status.isSuccess()) {
                    return "HTTP ${response.status.value}"
                }
                lastError = "HTTP ${response.status.value}: ${response.bodyAsText().take(120)}"
            } catch (t: Throwable) {
                lastError = t.message ?: t.toString()
            }
            if (attempt < maxAttempts - 1) {
                delay(400L * (1L shl attempt))
            }
        }
        return lastError
    }
}
