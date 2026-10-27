package dev.velum.core.integrations

import dev.velum.core.security.InMemorySecureStore
import dev.velum.core.security.SecureKeys
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebhookSettingsStoreTest {
    @Test
    fun saveAndClear_roundTrip() = runTest {
        val store = InMemorySecureStore()
        val webhooks = WebhookSettingsStore(store)
        assertFalse(webhooks.isConfigured())

        webhooks.save("https://hooks.example.com/velum", "s3cret")
        assertTrue(webhooks.isConfigured())
        assertEquals("https://hooks.example.com/velum", webhooks.getUrl())
        assertEquals("s3cret", webhooks.getSecret())
        assertEquals("s3cret", store.getString(SecureKeys.WEBHOOK_SECRET))

        webhooks.save("https://hooks.example.com/velum", "")
        assertNull(webhooks.getSecret())
        assertFalse(store.contains(SecureKeys.WEBHOOK_SECRET))

        webhooks.clear()
        assertFalse(webhooks.isConfigured())
        assertNull(store.getString(SecureKeys.WEBHOOK_URL))
    }
}
