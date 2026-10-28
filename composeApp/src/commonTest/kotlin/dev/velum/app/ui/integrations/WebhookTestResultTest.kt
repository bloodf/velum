package dev.velum.app.ui.integrations

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebhookTestResultTest {
    @Test
    fun accepts2xx() {
        assertTrue(WebhookTestResult.isHttpSuccess("HTTP 200"))
        assertTrue(WebhookTestResult.isHttpSuccess("HTTP 204"))
    }

    @Test
    fun rejectsErrorsAndNoise() {
        assertFalse(WebhookTestResult.isHttpSuccess("HTTP 404: missing"))
        assertFalse(WebhookTestResult.isHttpSuccess("HTTP 500"))
        assertFalse(WebhookTestResult.isHttpSuccess("Connection refused"))
        assertFalse(WebhookTestResult.isHttpSuccess(""))
    }
}
