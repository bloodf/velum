package dev.velum.core.integrations

import dev.velum.core.autoflow.AutoFlowPostAction

/**
 * Catalog of integrations that are safe to show in Settings / AutoFlow.
 * OAuth-gated providers stay out of the list until the owner registers a client ID.
 */
enum class IntegrationId(val stored: String) {
    LocalCalendar("local_calendar"),
    FolderExport("folder_export"),
    Webhook("webhook"),
}

data class IntegrationEntry(
    val id: IntegrationId,
    /** True when the user has finished setup (folder picked / URL saved / calendar usable). */
    val configured: Boolean,
    val capabilities: Set<String>,
)

class IntegrationRegistry(
    private val webhook: WebhookSettingsStore,
    private val exportFolderConfigured: () -> Boolean,
    private val googleCalendarClientConfigured: Boolean = false,
) {
    /**
     * Integrations with working end-to-end code paths.
     * Google Calendar OAuth is omitted until [googleCalendarClientConfigured] is true
     * (owner registration). Local device calendar remains listed separately.
     */
    suspend fun listed(): List<IntegrationEntry> = buildList {
        add(
            IntegrationEntry(
                id = IntegrationId.LocalCalendar,
                configured = true,
                capabilities = setOf("calendar_read", "calendar_insert"),
            ),
        )
        add(
            IntegrationEntry(
                id = IntegrationId.FolderExport,
                configured = exportFolderConfigured(),
                capabilities = setOf("markdown_export"),
            ),
        )
        add(
            IntegrationEntry(
                id = IntegrationId.Webhook,
                configured = webhook.isConfigured(),
                capabilities = setOf("webhook_post"),
            ),
        )
        // OAuth providers intentionally absent when client ID missing — no placeholder rows.
        if (googleCalendarClientConfigured) {
            // Skeleton only; Connect UI ships with AppAuth in a follow-up once registered.
        }
    }

    /** Post-actions AutoFlow may offer based on what the user has configured. */
    suspend fun availablePostActions(): List<AutoFlowPostAction> = buildList {
        add(AutoFlowPostAction.None)
        add(AutoFlowPostAction.ShareMarkdown)
        add(AutoFlowPostAction.CalendarInsert)
        if (exportFolderConfigured()) add(AutoFlowPostAction.FolderExport)
        if (webhook.isConfigured()) add(AutoFlowPostAction.Webhook)
    }
}
