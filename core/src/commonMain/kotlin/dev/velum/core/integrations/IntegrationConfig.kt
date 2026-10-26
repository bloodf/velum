package dev.velum.core.integrations

/**
 * OAuth client configuration for third-party integrations.
 * Populate via env / build config; never commit real secrets.
 *
 * Missing client IDs must surface as blocked — never fake a connected state.
 */
data class IntegrationConfig(
    val googleCalendarClientId: String? = null,
    val googleCalendarRedirectUri: String = DEFAULT_GOOGLE_REDIRECT,
) {
    fun googleCalendarReady(): Boolean = !googleCalendarClientId.isNullOrBlank()

    companion object {
        const val DEFAULT_GOOGLE_REDIRECT = "dev.velum.app:/oauth2redirect"

        /**
         * Resolve from a key lookup (process env on JVM/Android, empty on other targets).
         */
        fun fromEnv(getenv: (String) -> String?): IntegrationConfig = IntegrationConfig(
            googleCalendarClientId = getenv("VELUM_GOOGLE_CALENDAR_CLIENT_ID")
                ?.trim()
                ?.takeIf { it.isNotEmpty() },
            googleCalendarRedirectUri = getenv("VELUM_GOOGLE_CALENDAR_REDIRECT_URI")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: DEFAULT_GOOGLE_REDIRECT,
        )

        fun defaults(): IntegrationConfig = IntegrationConfig()
    }
}

enum class IntegrationAuthStatus {
    /** Client ID not registered by the app owner. */
    BlockedMissingClient,

    /** Credentials present; user has not completed OAuth. */
    Disconnected,

    /** Access token stored locally. */
    Connected,
}

data class OAuthTokenBundle(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAtEpochSeconds: Long? = null,
    val scope: String? = null,
)

/**
 * Builds the Google OAuth authorization URL when a client ID is configured.
 */
object GoogleCalendarOAuth {
    private const val AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
    private const val SCOPE = "https://www.googleapis.com/auth/calendar.readonly"

    fun authUrl(config: IntegrationConfig, state: String): String? {
        val clientId = config.googleCalendarClientId?.trim().orEmpty()
        if (clientId.isEmpty()) return null
        val redirect = config.googleCalendarRedirectUri
        return buildString {
            append(AUTH_ENDPOINT)
            append("?client_id=").append(encode(clientId))
            append("&redirect_uri=").append(encode(redirect))
            append("&response_type=code")
            append("&scope=").append(encode(SCOPE))
            append("&access_type=offline")
            append("&prompt=consent")
            append("&state=").append(encode(state))
        }
    }

    private fun encode(value: String): String =
        buildString(value.length) {
            for (ch in value) {
                when {
                    ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch == '.' || ch == '~' -> append(ch)
                    ch == ' ' -> append("%20")
                    else -> append('%').append(ch.code.toString(16).uppercase().padStart(2, '0'))
                }
            }
        }
}
