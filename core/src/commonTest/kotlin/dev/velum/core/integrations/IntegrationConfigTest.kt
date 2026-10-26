package dev.velum.core.integrations

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IntegrationConfigTest {
    @Test
    fun missingClientIdIsNotReady() {
        val cfg = IntegrationConfig.defaults()
        assertFalse(cfg.googleCalendarReady())
        assertNull(GoogleCalendarOAuth.authUrl(cfg, "state"))
    }

    @Test
    fun presentClientIdBuildsAuthUrl() {
        val cfg = IntegrationConfig(googleCalendarClientId = "client-123")
        assertTrue(cfg.googleCalendarReady())
        val url = GoogleCalendarOAuth.authUrl(cfg, "abc")
        assertNotNull(url)
        assertTrue(url.contains("client_id=client-123"))
        assertTrue(url.contains("state=abc"))
        assertTrue(url.contains("accounts.google.com"))
    }

    @Test
    fun fromEnvReadsClientId() {
        val cfg = IntegrationConfig.fromEnv { key ->
            if (key == "VELUM_GOOGLE_CALENDAR_CLIENT_ID") "env-client" else null
        }
        assertTrue(cfg.googleCalendarReady())
        assertTrue(cfg.googleCalendarClientId == "env-client")
    }
}
