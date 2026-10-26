package dev.velum.core.settings

/**
 * Validates OpenAI-compatible base URLs at settings and HTTP boundaries.
 *
 * HTTPS is required except for loopback (`localhost` / `127.0.0.1`).
 * Cloud metadata endpoints (e.g. `169.254.169.254`) are always rejected.
 */
object AiBaseUrl {
    private val metadataHosts = setOf(
        "169.254.169.254",
        "metadata.google.internal",
    )

    fun normalizeAndValidate(url: String): String {
        val normalized = url.trim().trimEnd('/')
        require(normalized.isNotEmpty()) { "Base URL must not be blank" }

        val schemeSep = normalized.indexOf("://")
        require(schemeSep > 0) { "Base URL must include a scheme (https://…)" }
        val scheme = normalized.substring(0, schemeSep).lowercase()
        val rest = normalized.substring(schemeSep + 3)
        val hostPort = rest.substringBefore('/').substringBefore('?')
        val host = hostPort.substringBefore(':').lowercase().trim('[', ']')

        require(host.isNotEmpty()) { "Base URL host must not be blank" }
        require(host !in metadataHosts) {
            "Base URL host '$host' is blocked (cloud metadata)"
        }
        require(!host.startsWith("169.254.")) {
            "Base URL host '$host' is blocked (link-local / metadata range)"
        }

        val loopback = host == "localhost" || host == "127.0.0.1" || host == "::1"
        when (scheme) {
            "https" -> Unit
            "http" -> require(loopback) {
                "HTTP base URLs are only allowed for localhost/127.0.0.1 (got host=$host)"
            }
            else -> error("Base URL scheme must be https (or http for localhost), got '$scheme'")
        }
        return normalized
    }
}
