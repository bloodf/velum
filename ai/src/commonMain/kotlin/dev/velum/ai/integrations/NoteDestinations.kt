package dev.velum.ai.integrations

import dev.velum.ai.createAiHttpClient
import dev.velum.core.data.TranscriptMarkers
import dev.velum.core.data.VelumRepository
import dev.velum.core.data.WipeGate
import dev.velum.core.integrations.NotionBlocks
import dev.velum.core.integrations.NotionSettingsStore
import dev.velum.core.integrations.SharedNote
import dev.velum.core.integrations.SlackMessage
import dev.velum.core.integrations.SlackSettingsStore
import dev.velum.core.time.VelumDateTime
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Outcome of sending a note to Slack or Notion. [detail] follows the webhook convention the UI already understands:
 * `HTTP <n>` or a [WebhookFailure] code, never a response body or URL (both can carry secrets or note text).
 */
sealed class DestinationResult {
    data class Sent(val url: String? = null) : DestinationResult()
    data class Failed(val detail: String) : DestinationResult()
    /** The destination is not set up, or the recording is gone. */
    data object NotConfigured : DestinationResult()
}

/** Headings written into exported pages, in the app's language. */
data class DestinationLabels(
    val summary: String = "Summary",
    val transcript: String = "Transcript",
    val noSummary: String = "(no summary yet)",
)

private suspend fun sharedNote(repository: VelumRepository, recordingId: String): SharedNote? {
    val rec = repository.getRecording(recordingId) ?: return null
    return SharedNote(
        title = rec.title,
        whenLabel = VelumDateTime.formatDateTime(rec.started_at),
        durationSeconds = rec.duration_seconds,
        speakers = repository.getSpeakers(recordingId).mapNotNull { it.display_name?.takeIf { n -> n.isNotBlank() } ?: it.label },
        summaryMarkdown = rec.summary_markdown,
        transcriptMarkdown = TranscriptMarkers.spoken(rec.transcript),
    )
}

/** Posts a note's summary to the Slack channel of the user's Incoming Webhook (#61). */
class SlackPoster(
    private val repository: VelumRepository,
    private val settings: SlackSettingsStore,
    private val httpClient: HttpClient = createAiHttpClient(),
    private val wipeGate: WipeGate = WipeGate(),
    private val maxAttempts: Int = 3,
) {
    suspend fun post(recordingId: String, labels: DestinationLabels = DestinationLabels()): DestinationResult =
        wipeGate.guard {
            val url = settings.getUrl() ?: return@guard DestinationResult.NotConfigured
            val note = sharedNote(repository, recordingId) ?: return@guard DestinationResult.NotConfigured
            send(url, SlackMessage.payload(note, labels.noSummary).toString())
        } ?: DestinationResult.NotConfigured

    /** A short message proving the channel is reachable, without any recording. */
    suspend fun test(text: String): DestinationResult {
        val url = settings.getUrl() ?: return DestinationResult.NotConfigured
        return send(url, buildJsonObject { put("text", text) }.toString())
    }

    private suspend fun send(url: String, body: String): DestinationResult {
        var last: DestinationResult = DestinationResult.Failed(WebhookFailure.CONNECTION)
        repeat(maxAttempts) { attempt ->
            try {
                val response = httpClient.post(url) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                if (response.status.isSuccess()) return DestinationResult.Sent()
                last = DestinationResult.Failed("HTTP ${response.status.value}")
                // 4xx other than rate limiting will not change on retry (bad URL, revoked webhook, bad payload).
                if (response.status.value in 400..499 && response.status.value != 429) return last
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                last = DestinationResult.Failed(WebhookFailure.of(t))
            }
            if (attempt < maxAttempts - 1) delay(400L * (1L shl attempt))
        }
        return last
    }
}

/**
 * Creates a page for a note in the user's Notion database (#59) with a user-created internal integration token. The
 * database's title column is looked up, a date column (if any) gets the recording start, and blocks beyond Notion's
 * 100-per-request limit are appended afterwards.
 */
@OptIn(ExperimentalTime::class)
class NotionExporter(
    private val repository: VelumRepository,
    private val settings: NotionSettingsStore,
    private val httpClient: HttpClient = createAiHttpClient(),
    private val wipeGate: WipeGate = WipeGate(),
    private val retryDelayMs: (attempt: Int, retryAfterSeconds: Long?) -> Long = { attempt, retryAfter ->
        (retryAfter?.times(1000) ?: (500L shl attempt)).coerceAtMost(10_000)
    },
) {
    suspend fun export(recordingId: String, labels: DestinationLabels = DestinationLabels()): DestinationResult =
        wipeGate.guard {
            val token = settings.getToken() ?: return@guard DestinationResult.NotConfigured
            val databaseId = settings.getDatabaseId() ?: return@guard DestinationResult.NotConfigured
            val rec = repository.getRecording(recordingId) ?: return@guard DestinationResult.NotConfigured
            val note = sharedNote(repository, recordingId) ?: return@guard DestinationResult.NotConfigured
            try {
                val schema = when (val r = databaseSchema(token, databaseId)) {
                    is Call.Ok -> r.value
                    is Call.Err -> return@guard DestinationResult.Failed(r.detail)
                }
                val blocks = NotionBlocks.noteBlocks(note, labels.summary, labels.transcript)
                val request = NotionBlocks.createPageRequest(
                    databaseId = databaseId,
                    titleProperty = schema.titleProperty,
                    title = note.title,
                    dateProperty = schema.dateProperty,
                    startIso = Instant.fromEpochSeconds(rec.started_at).toString(),
                    children = blocks.take(100),
                )
                val created = when (val r = call { httpClient.post("$API/pages") { notion(token); setBody(request.toString()) } }) {
                    is Call.Ok -> r.value
                    is Call.Err -> return@guard DestinationResult.Failed(r.detail)
                }
                val page = json.parseToJsonElement(created).jsonObject
                val pageId = page["id"]?.jsonPrimitive?.contentOrNull
                    ?: return@guard DestinationResult.Failed(WebhookFailure.CONNECTION)
                for (batch in blocks.drop(100).chunked(100)) {
                    val body = buildJsonObject { put("children", buildJsonArray { batch.forEach { add(it) } }) }
                    val appended = call { httpClient.patch("$API/blocks/$pageId/children") { notion(token); setBody(body.toString()) } }
                    if (appended is Call.Err) return@guard DestinationResult.Failed(appended.detail)
                }
                DestinationResult.Sent(page["url"]?.jsonPrimitive?.contentOrNull)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                DestinationResult.Failed(WebhookFailure.of(t))
            }
        } ?: DestinationResult.NotConfigured

    /** Checks the token and database (the database must be shared with the integration). */
    suspend fun test(): DestinationResult {
        val token = settings.getToken() ?: return DestinationResult.NotConfigured
        val databaseId = settings.getDatabaseId() ?: return DestinationResult.NotConfigured
        return try {
            when (val r = databaseSchema(token, databaseId)) {
                is Call.Ok -> DestinationResult.Sent()
                is Call.Err -> DestinationResult.Failed(r.detail)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            DestinationResult.Failed(WebhookFailure.of(t))
        }
    }

    internal data class Schema(val titleProperty: String, val dateProperty: String?)

    /** Per call, never shared state: exports can run concurrently. */
    private sealed class Call<out T> {
        data class Ok<T>(val value: T) : Call<T>()
        data class Err(val detail: String) : Call<Nothing>()
    }

    private suspend fun databaseSchema(token: String, databaseId: String): Call<Schema> {
        val body = when (val r = call { httpClient.get("$API/databases/$databaseId") { notion(token) } }) {
            is Call.Ok -> r.value
            is Call.Err -> return r
        }
        val props = json.parseToJsonElement(body).jsonObject["properties"]?.jsonObject
            ?: return Call.Err(NOT_A_DATABASE)
        fun typeOf(v: JsonObject) = v["type"]?.jsonPrimitive?.contentOrNull
        val title = props.entries.firstOrNull { typeOf(it.value.jsonObject) == "title" }?.key
            ?: return Call.Err(NOT_A_DATABASE)
        val date = props.entries.firstOrNull { typeOf(it.value.jsonObject) == "date" }?.key
        return Call.Ok(Schema(title, date))
    }

    /** The response body, or `HTTP <n>`. Rate limits (429) and 5xx are retried, honouring Retry-After. */
    private suspend fun call(request: suspend () -> HttpResponse): Call<String> {
        var failure = "HTTP 0"
        repeat(MAX_ATTEMPTS) { attempt ->
            val response = request()
            if (response.status.isSuccess()) return Call.Ok(response.bodyAsText())
            failure = "HTTP ${response.status.value}"
            val retryable = response.status.value == 429 || response.status.value >= 500
            if (!retryable || attempt == MAX_ATTEMPTS - 1) return Call.Err(failure)
            delay(retryDelayMs(attempt, response.headers["Retry-After"]?.toLongOrNull()))
        }
        return Call.Err(failure)
    }

    private fun io.ktor.client.request.HttpRequestBuilder.notion(token: String) {
        bearerAuth(token)
        header("Notion-Version", NOTION_VERSION)
        contentType(ContentType.Application.Json)
    }

    companion object {
        const val API = "https://api.notion.com/v1"
        const val NOTION_VERSION = "2022-06-28"
        private const val MAX_ATTEMPTS = 3
        /** The id is not a database the integration can read as one (e.g. a page id). */
        const val NOT_A_DATABASE = "not_a_database"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
