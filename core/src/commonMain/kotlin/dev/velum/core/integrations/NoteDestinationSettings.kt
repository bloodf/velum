package dev.velum.core.integrations

import dev.velum.core.security.SecureKeys
import dev.velum.core.security.SecureStore

/**
 * Slack Incoming Webhook the user created for one channel (#61). No OAuth and no Velum server: the URL itself is the
 * credential, so it lives in [SecureStore] and is never logged or shown back in full.
 */
class SlackSettingsStore(private val store: SecureStore) {
    suspend fun getUrl(): String? = store.getString(SecureKeys.SLACK_WEBHOOK_URL)?.trim()?.takeIf { it.isNotEmpty() }

    suspend fun isConfigured(): Boolean = getUrl() != null

    suspend fun save(url: String) {
        store.putString(SecureKeys.SLACK_WEBHOOK_URL, SlackWebhookUrl.normalizeAndValidate(url))
    }

    suspend fun clear() = store.remove(SecureKeys.SLACK_WEBHOOK_URL)
}

/** Only Slack's own webhook host: a note must never go to another host because a URL was pasted into this field. */
object SlackWebhookUrl {
    const val HOST = "hooks.slack.com"

    fun normalizeAndValidate(url: String): String {
        val normalized = url.trim()
        require(normalized.startsWith("https://")) { "Slack webhook URL must start with https://" }
        val host = normalized.removePrefix("https://").substringBefore('/').substringBefore('?').lowercase()
        require(host == HOST) { "Slack webhook URL must be on $HOST" }
        val path = normalized.removePrefix("https://").substringAfter('/', "")
        require(path.startsWith("services/") || path.startsWith("triggers/") || path.startsWith("workflows/")) {
            "Not a Slack webhook URL"
        }
        return normalized
    }
}

/**
 * Notion internal integration token plus the database new pages go to (#59). A user-created internal integration
 * needs no OAuth client secret, so Velum stays serverless; the user shares the database with the integration.
 */
class NotionSettingsStore(private val store: SecureStore) {
    suspend fun getToken(): String? = store.getString(SecureKeys.NOTION_TOKEN)?.trim()?.takeIf { it.isNotEmpty() }

    suspend fun getDatabaseId(): String? =
        store.getString(SecureKeys.NOTION_DATABASE_ID)?.trim()?.takeIf { it.isNotEmpty() }

    suspend fun isConfigured(): Boolean = getToken() != null && getDatabaseId() != null

    /** [database] may be the database URL copied from Notion or its id. */
    suspend fun save(token: String, database: String) {
        val t = NotionIds.validateToken(token)
        val id = NotionIds.databaseId(database)
        store.putString(SecureKeys.NOTION_TOKEN, t)
        store.putString(SecureKeys.NOTION_DATABASE_ID, id)
    }

    suspend fun clear() {
        store.remove(SecureKeys.NOTION_TOKEN)
        store.remove(SecureKeys.NOTION_DATABASE_ID)
    }
}

object NotionIds {
    /** Internal integration tokens start with `ntn_` (current) or `secret_` (older). */
    fun validateToken(token: String): String {
        val t = token.trim()
        require((t.startsWith("ntn_") || t.startsWith("secret_")) && t.length >= 20 && t.none { it.isWhitespace() }) {
            "Not a Notion internal integration token"
        }
        return t
    }

    /**
     * The 32-hex-digit database id, formatted with dashes, from an id or a Notion URL such as
     * `https://www.notion.so/workspace/Name-0123456789abcdef0123456789abcdef?v=...`.
     */
    fun databaseId(input: String): String {
        val beforeQuery = input.trim().substringBefore('?').substringBefore('#')
        val candidates = Regex("[0-9a-fA-F]{8}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{12}")
            .findAll(beforeQuery).map { it.value.replace("-", "").lowercase() }.toList()
        val hex = requireNotNull(candidates.lastOrNull()) { "No Notion database id found" }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
    }
}
