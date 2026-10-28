package dev.velum.core.integrations

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WebhookPayloadTest {
    @Test
    fun encode_includesSchemaVersionAndFields() {
        val payload = WebhookPayload.fromRecording(
            recordingId = "rec-1",
            title = "Sync",
            startedAt = 100L,
            durationSeconds = 60,
            source = "phone",
            speakers = listOf("Ada"),
            transcript = "Hello",
            summaryMarkdown = "## Notes",
        )
        val json = WebhookPayload.encode(payload)
        assertContains(json, "\"schema_version\":1")
        assertContains(json, "\"recording_id\":\"rec-1\"")
        assertContains(json, "\"title\":\"Sync\"")
        assertContains(json, "\"speakers\":[\"Ada\"]")
        assertContains(json, "\"transcript\":\"Hello\"")
        assertEquals(WebhookPayload.SCHEMA_VERSION, payload.schemaVersion)
    }

    @Test
    fun testPing_isTaggedTestPayload() {
        val payload = WebhookPayload.testPing(startedAt = 42L)
        assertEquals("velum-test", payload.recordingId)
        assertEquals("test", payload.source)
        assertEquals(listOf("velum", "test"), payload.tags)
        assertEquals(42L, payload.startedAt)
        val json = WebhookPayload.encode(payload)
        assertContains(json, "\"recording_id\":\"velum-test\"")
        assertContains(json, "\"test\"")
    }

    @Test
    fun webhookUrl_rejectsMetadataAndPlainHttp() {
        assertFailsWith<IllegalArgumentException> {
            WebhookUrl.normalizeAndValidate("http://example.com/hook")
        }
        assertFailsWith<IllegalArgumentException> {
            WebhookUrl.normalizeAndValidate("https://169.254.169.254/latest")
        }
        val ok = WebhookUrl.normalizeAndValidate("https://hooks.example.com/velum")
        assertTrue(ok.startsWith("https://"))
        val local = WebhookUrl.normalizeAndValidate("http://127.0.0.1:5678/webhook")
        assertTrue(local.contains("127.0.0.1"))
    }
}
