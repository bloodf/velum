package dev.velum.core.integrations

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebhookSignerTest {
    @Test
    fun signsAndVerifiesDeterministically() {
        val body = """{"event":"note.ready","id":"rec-1"}"""
        val secret = "test-secret"
        val header = WebhookSigner.signatureHeaderValue(secret, body)
        assertTrue(header.startsWith("sha256="))
        assertEquals(64 + "sha256=".length, header.length)
        assertTrue(WebhookSigner.verify(secret, body, header))
        assertFalse(WebhookSigner.verify(secret, body + "x", header))
        assertFalse(WebhookSigner.verify("other", body, header))
    }
}
