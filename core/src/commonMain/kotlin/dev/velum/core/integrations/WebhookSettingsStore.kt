package dev.velum.core.integrations

import dev.velum.core.security.SecureKeys
import dev.velum.core.security.SecureStore

/**
 * User-configured outbound webhook URL + optional HMAC secret.
 * Secrets live in [SecureStore] (Android Keystore-backed) and must never be logged.
 */
class WebhookSettingsStore(
    private val store: SecureStore,
) {
    suspend fun getUrl(): String? =
        store.getString(SecureKeys.WEBHOOK_URL)?.trim()?.takeIf { it.isNotEmpty() }

    suspend fun getSecret(): String? =
        store.getString(SecureKeys.WEBHOOK_SECRET)?.takeIf { it.isNotEmpty() }

    suspend fun isConfigured(): Boolean = getUrl() != null

    suspend fun hasSecret(): Boolean = !getSecret().isNullOrEmpty()

    /**
     * Persist a validated HTTPS (or localhost HTTP) webhook URL and optional HMAC secret.
     * Blank [secret] clears any previously stored secret.
     */
    suspend fun save(url: String, secret: String?) {
        val normalized = WebhookUrl.normalizeAndValidate(url)
        store.putString(SecureKeys.WEBHOOK_URL, normalized)
        val trimmedSecret = secret?.trim().orEmpty()
        if (trimmedSecret.isEmpty()) {
            store.remove(SecureKeys.WEBHOOK_SECRET)
        } else {
            store.putString(SecureKeys.WEBHOOK_SECRET, trimmedSecret)
        }
    }

    suspend fun clear() {
        store.remove(SecureKeys.WEBHOOK_URL)
        store.remove(SecureKeys.WEBHOOK_SECRET)
    }
}

/**
 * Same host rules as AI base URLs: HTTPS required except loopback; block metadata hosts.
 */
object WebhookUrl {
    private val metadataHosts = setOf(
        "169.254.169.254",
        "metadata.google.internal",
    )

    fun normalizeAndValidate(url: String): String {
        val normalized = url.trim()
        require(normalized.isNotEmpty()) { "Webhook URL must not be blank" }

        val schemeSep = normalized.indexOf("://")
        require(schemeSep > 0) { "Webhook URL must include a scheme (https://…)" }
        val scheme = normalized.substring(0, schemeSep).lowercase()
        val rest = normalized.substring(schemeSep + 3)
        val hostPort = rest.substringBefore('/').substringBefore('?')
        val host = hostPort.substringBefore(':').lowercase().trim('[', ']')

        require(host.isNotEmpty()) { "Webhook URL host must not be blank" }
        require(host !in metadataHosts) {
            "Webhook URL host '$host' is blocked (cloud metadata)"
        }
        require(!host.startsWith("169.254.")) {
            "Webhook URL host '$host' is blocked (link-local / metadata range)"
        }

        val loopback = host == "localhost" || host == "127.0.0.1" || host == "::1"
        when (scheme) {
            "https" -> Unit
            "http" -> require(loopback) {
                "HTTP webhook URLs are only allowed for localhost (got host=$host)"
            }
            else -> error("Webhook URL scheme must be https (or http for localhost), got '$scheme'")
        }
        return normalized
    }
}
