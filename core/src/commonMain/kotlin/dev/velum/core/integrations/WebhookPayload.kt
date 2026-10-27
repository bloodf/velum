package dev.velum.core.integrations

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Versioned JSON body for outbound webhooks (Zapier / n8n / Make).
 * Documented in `docs/WEBHOOK.md`.
 */
@Serializable
data class WebhookPayload(
    @SerialName("schema_version") val schemaVersion: Int = SCHEMA_VERSION,
    @SerialName("recording_id") val recordingId: String,
    val title: String,
    @SerialName("started_at") val startedAt: Long?,
    @SerialName("duration_seconds") val durationSeconds: Long,
    val source: String?,
    val speakers: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val transcript: String? = null,
    @SerialName("summary_markdown") val summaryMarkdown: String? = null,
) {
    companion object {
        const val SCHEMA_VERSION = 1

        private val json = Json {
            encodeDefaults = true
            prettyPrint = false
        }

        fun encode(payload: WebhookPayload): String = json.encodeToString(payload)

        fun fromRecording(
            recordingId: String,
            title: String,
            startedAt: Long?,
            durationSeconds: Long,
            source: String?,
            speakers: List<String>,
            transcript: String?,
            summaryMarkdown: String?,
            tags: List<String> = listOf("velum"),
        ): WebhookPayload = WebhookPayload(
            recordingId = recordingId,
            title = title,
            startedAt = startedAt,
            durationSeconds = durationSeconds,
            source = source,
            speakers = speakers,
            tags = tags,
            transcript = transcript,
            summaryMarkdown = summaryMarkdown,
        )
    }
}
