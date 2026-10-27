package dev.velum.core.integrations

import dev.velum.core.autoflow.AutoFlowPostAction
import dev.velum.core.security.InMemorySecureStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IntegrationRegistryTest {
    @Test
    fun listed_includesLocalOnlyIntegrations_omitsOauthWithoutClient() = runTest {
        val webhook = WebhookSettingsStore(InMemorySecureStore())
        val registry = IntegrationRegistry(
            webhook = webhook,
            exportFolderConfigured = { false },
            googleCalendarClientConfigured = false,
        )
        val ids = registry.listed().map { it.id }
        assertTrue(IntegrationId.LocalCalendar in ids)
        assertTrue(IntegrationId.FolderExport in ids)
        assertTrue(IntegrationId.Webhook in ids)
        assertEquals(3, ids.size)
        assertFalse(registry.listed().first { it.id == IntegrationId.FolderExport }.configured)
        assertFalse(registry.listed().first { it.id == IntegrationId.Webhook }.configured)
    }

    @Test
    fun availablePostActions_gatesOnConfiguration() = runTest {
        val store = InMemorySecureStore()
        val webhook = WebhookSettingsStore(store)
        val registry = IntegrationRegistry(
            webhook = webhook,
            exportFolderConfigured = { true },
            googleCalendarClientConfigured = false,
        )
        val before = registry.availablePostActions()
        assertTrue(AutoFlowPostAction.FolderExport in before)
        assertFalse(AutoFlowPostAction.Webhook in before)

        webhook.save("https://example.com/hook", null)
        val after = registry.availablePostActions()
        assertTrue(AutoFlowPostAction.Webhook in after)
    }
}
