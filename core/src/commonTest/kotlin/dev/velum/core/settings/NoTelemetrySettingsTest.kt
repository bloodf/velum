package dev.velum.core.settings

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Velum ships with zero telemetry / analytics / crash-reporting.
 * Preferences must never expose opt-in usage-data or related settings.
 */
class NoTelemetrySettingsTest {
    private val bannedNameFragments = listOf(
        "helpImprove",
        "help_improve",
        "telemetry",
        "analytics",
        "crashlytics",
        "crashReport",
        "crash_report",
        "usageData",
        "usage_data",
        "datadog",
        "posthog",
        "firebaseMessaging",
    )

    private val json = Json { encodeDefaults = true }

    @Test
    fun appPreferences_hasNoTelemetryFields() {
        val encoded = json.encodeToString(
            AppPreferences(
                cleanTranscript = false,
                autoSpeakerLabel = true,
                displayName = "owner",
            ),
        )
        for (banned in bannedNameFragments) {
            assertFalse(
                encoded.contains(banned, ignoreCase = true),
                "AppPreferences JSON must not contain telemetry-related key/value fragment: $banned (got $encoded)",
            )
        }
        assertTrue(encoded.contains("cleanTranscript"), "sanity: known preference still serializes")
        assertTrue(encoded.contains("autoSpeakerLabel"))
    }

    @Test
    fun settingsRepository_loadIgnoresLegacyHelpImproveKey() = runTest {
        val store = dev.velum.core.security.InMemorySecureStore()
        store.putString("prefs.help_improve", "true") // leftover from older builds
        val repo = SettingsRepository(store)
        repo.load()
        val encoded = json.encodeToString(repo.preferences.value)
        assertFalse(encoded.contains("helpImprove", ignoreCase = true))
        assertFalse(encoded.contains("help_improve", ignoreCase = true))
    }
}
