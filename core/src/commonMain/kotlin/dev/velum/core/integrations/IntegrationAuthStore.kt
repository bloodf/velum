package dev.velum.core.integrations

import dev.velum.core.security.SecureStore

object IntegrationSecureKeys {
    const val GOOGLE_CALENDAR_ACCESS = "oauth.google_calendar.access_token"
    const val GOOGLE_CALENDAR_REFRESH = "oauth.google_calendar.refresh_token"
    const val GOOGLE_CALENDAR_EXPIRES = "oauth.google_calendar.expires_at"
    const val GOOGLE_CALENDAR_SCOPE = "oauth.google_calendar.scope"
}

/**
 * Token skeleton for Google Calendar OAuth. Completes when a real client ID
 * and redirect handler exchange an auth code for tokens.
 */
class IntegrationAuthStore(
    private val store: SecureStore,
    private val config: IntegrationConfig,
) {
    suspend fun statusSuspend(): IntegrationAuthStatus {
        if (!config.googleCalendarReady()) return IntegrationAuthStatus.BlockedMissingClient
        val token = store.getString(IntegrationSecureKeys.GOOGLE_CALENDAR_ACCESS)
        return if (token.isNullOrBlank()) {
            IntegrationAuthStatus.Disconnected
        } else {
            IntegrationAuthStatus.Connected
        }
    }

    fun authorizationUrl(state: String = "velum"): String? =
        GoogleCalendarOAuth.authUrl(config, state)

    suspend fun saveTokens(bundle: OAuthTokenBundle) {
        store.putString(IntegrationSecureKeys.GOOGLE_CALENDAR_ACCESS, bundle.accessToken)
        if (bundle.refreshToken.isNullOrBlank()) {
            store.remove(IntegrationSecureKeys.GOOGLE_CALENDAR_REFRESH)
        } else {
            store.putString(IntegrationSecureKeys.GOOGLE_CALENDAR_REFRESH, bundle.refreshToken)
        }
        if (bundle.expiresAtEpochSeconds == null) {
            store.remove(IntegrationSecureKeys.GOOGLE_CALENDAR_EXPIRES)
        } else {
            store.putString(
                IntegrationSecureKeys.GOOGLE_CALENDAR_EXPIRES,
                bundle.expiresAtEpochSeconds.toString(),
            )
        }
        if (bundle.scope.isNullOrBlank()) {
            store.remove(IntegrationSecureKeys.GOOGLE_CALENDAR_SCOPE)
        } else {
            store.putString(IntegrationSecureKeys.GOOGLE_CALENDAR_SCOPE, bundle.scope)
        }
    }

    suspend fun clearTokens() {
        listOf(
            IntegrationSecureKeys.GOOGLE_CALENDAR_ACCESS,
            IntegrationSecureKeys.GOOGLE_CALENDAR_REFRESH,
            IntegrationSecureKeys.GOOGLE_CALENDAR_EXPIRES,
            IntegrationSecureKeys.GOOGLE_CALENDAR_SCOPE,
        ).forEach { store.remove(it) }
    }

    suspend fun loadTokens(): OAuthTokenBundle? {
        val access = store.getString(IntegrationSecureKeys.GOOGLE_CALENDAR_ACCESS) ?: return null
        if (access.isBlank()) return null
        return OAuthTokenBundle(
            accessToken = access,
            refreshToken = store.getString(IntegrationSecureKeys.GOOGLE_CALENDAR_REFRESH),
            expiresAtEpochSeconds = store.getString(IntegrationSecureKeys.GOOGLE_CALENDAR_EXPIRES)
                ?.toLongOrNull(),
            scope = store.getString(IntegrationSecureKeys.GOOGLE_CALENDAR_SCOPE),
        )
    }
}
