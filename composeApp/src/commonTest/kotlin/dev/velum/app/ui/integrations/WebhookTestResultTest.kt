package dev.velum.app.ui.integrations

import kotlin.test.Test
import kotlin.test.assertEquals
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
        assertFalse(WebhookTestResult.isHttpSuccess("HTTP 404"))
        assertFalse(WebhookTestResult.isHttpSuccess("HTTP 500"))
        assertFalse(WebhookTestResult.isHttpSuccess("connection"))
        assertFalse(WebhookTestResult.isHttpSuccess(""))
    }

    @Test
    fun failureCodesAreLocalizedAndHttpStatusesAreKept() {
        for (strings in listOf(dev.velum.app.i18n.En, dev.velum.app.i18n.PtBr)) {
            assertEquals(strings.added.webhookFailedTimeout, WebhookTestResult.failureText("timeout", strings))
            assertEquals(strings.added.webhookFailedUnresolvedHost, WebhookTestResult.failureText("unresolved_host", strings))
            assertEquals(strings.added.webhookFailedTls, WebhookTestResult.failureText("tls", strings))
            assertEquals(strings.added.webhookFailedConnection, WebhookTestResult.failureText("connection", strings))
            assertEquals("HTTP 503", WebhookTestResult.failureText("HTTP 503", strings))
        }
        assertEquals("tempo esgotado", WebhookTestResult.failureText("timeout", dev.velum.app.i18n.PtBr))
    }
}
