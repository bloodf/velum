package dev.velum.ai.integrations

import dev.velum.ai.createAiHttpClient
import dev.velum.ai.pipeline.TranscriptFormatter
import dev.velum.core.data.VelumRepository
import dev.velum.core.data.WipeGate
import dev.velum.core.integrations.WebhookPayload
import dev.velum.core.integrations.WebhookSettingsStore
import dev.velum.core.integrations.WebhookSigner
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
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
    private val wipeGate: WipeGate = WipeGate(),
) {
    /**
     * @return short success message, or null on failure (caller shows localized fallback). A wipe stops a
     * delivery and its retries, and none starts while it runs.
     */
    suspend fun dispatch(recordingId: String): String? = wipeGate.guard { dispatchNow(recordingId) }

    private suspend fun dispatchNow(recordingId: String): String? {
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
            transcript = rec.transcript?.let { TranscriptFormatter.splitMarkers(it).first.ifBlank { null } },
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
                // Status only: the reply can echo the request, and the webhook URL is itself a secret (#365).
                lastError = "HTTP ${response.status.value}"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                // A stable code: client messages quote the request URL, query string included (#365).
                lastError = WebhookFailure.of(t)
            }
            if (attempt < maxAttempts - 1) {
                delay(400L * (1L shl attempt))
            }
        }
        return lastError
    }
}

/**
 * Why a webhook request failed, as a stable code the UI turns into localized copy ([HTTP] statuses are `HTTP <n>`).
 * Never the exception's message, which quotes the request URL, query string included (#365), and never a class name
 * a shrinker may rename: Ktor types are matched with `is`, platform ones (which keep their names) by name.
 */
object WebhookFailure {
    const val TIMEOUT = "timeout"
    const val UNRESOLVED_HOST = "unresolved_host"
    const val TLS = "tls"
    const val CONNECTION = "connection"

    fun of(t: Throwable): String {
        val chain = generateSequence(t) { it.cause }.take(6).toList()
        return when {
            chain.any { it is HttpRequestTimeoutException || it is ConnectTimeoutException || it is SocketTimeoutException } -> TIMEOUT
            chain.any { it.javaName().endsWith("SocketTimeoutException") } -> TIMEOUT
            chain.any { it.javaName().endsWith("UnknownHostException") } -> UNRESOLVED_HOST
            chain.any { it.javaName().let { n -> "SSL" in n || "CertPath" in n || "CertificateException" in n } } -> TLS
            else -> CONNECTION
        }
    }

    private fun Throwable.javaName(): String = this::class.qualifiedName ?: this::class.simpleName.orEmpty()
}
