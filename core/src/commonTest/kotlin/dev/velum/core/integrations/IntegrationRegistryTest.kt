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

    @Test
    fun slackAndNotion_listedWhenWired_andOfferedOnlyOnceConfigured() = runTest {
        val store = InMemorySecureStore()
        val slack = SlackSettingsStore(store)
        val notion = NotionSettingsStore(store)
        val registry = IntegrationRegistry(
            webhook = WebhookSettingsStore(store),
            exportFolderConfigured = { false },
            slack = slack,
            notion = notion,
        )
        val listed = registry.listed()
        assertFalse(listed.first { it.id == IntegrationId.Slack }.configured)
        assertFalse(listed.first { it.id == IntegrationId.Notion }.configured)
        assertFalse(AutoFlowPostAction.Slack in registry.availablePostActions())
        assertFalse(AutoFlowPostAction.Notion in registry.availablePostActions())

        slack.save("https://hooks.slack.com/services/T0/B0/abc")
        notion.save("ntn_abcdefghijklmnopqrstuvwxyz", "0123456789abcdef0123456789abcdef")
        val actions = registry.availablePostActions()
        assertTrue(AutoFlowPostAction.Slack in actions)
        assertTrue(AutoFlowPostAction.Notion in actions)
        assertTrue(registry.listed().first { it.id == IntegrationId.Notion }.configured)
    }
}
