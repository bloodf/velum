package dev.velum.core.settings

import dev.velum.core.security.SecureKeys
import dev.velum.core.security.SecureStore
import dev.velum.core.security.SecureStoreUnavailableException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsStorageFailureTest {
    @Test
    fun failed_initial_load_is_unavailable_not_a_successful_missing_credentials_snapshot() = runTest {
        val store = FixtureStore(); store.values[SecureKeys.AI_API_KEY] = "synthetic-api"
        val before = store.values.toMap(); store.failKey = "ai.endpoint_profiles_json"
        val repo = SettingsRepository(store)
        assertFailsWith<SecureStoreUnavailableException> { repo.load() }
        assertEquals(SettingsLoadState.Unavailable, repo.loadState.value)
        assertEquals(before, store.values)
        store.failKey = null
        repo.load()
        assertEquals(SettingsLoadState.Ready, repo.loadState.value)
        assertTrue(repo.settings.value.hasApiKey)
        assertEquals("synthetic-api", repo.getApiKey())
    }

    @Test
    fun failed_refresh_preserves_the_entire_last_successful_settings_and_preferences_snapshot() = runTest {
        val store = FixtureStore(); val repo = SettingsRepository(store)
        repo.load(); repo.setThemeMode("dark")
        val oldSettings = repo.settings.value; val oldPreferences = repo.preferences.value
        SettingsRepository(store).setBaseUrl("https://synthetic.example/v1")
        val before = store.values.toMap(); store.failKey = "prefs.app_language"
        assertFailsWith<SecureStoreUnavailableException> { repo.load() }
        assertEquals(SettingsLoadState.Unavailable, repo.loadState.value)
        assertEquals(oldSettings, repo.settings.value)
        assertEquals(oldPreferences, repo.preferences.value)
        assertEquals(before, store.values)
        store.failKey = null; repo.load()
        assertEquals("https://synthetic.example/v1", repo.settings.value.baseUrl)
        assertEquals(SettingsLoadState.Ready, repo.loadState.value)
    }

    @Test
    fun cancelled_load_restores_previous_readiness_and_never_publishes_partial_state() = runTest {
        val store = FixtureStore(); val repo = SettingsRepository(store); repo.load()
        val settings = repo.settings.value; val prefs = repo.preferences.value
        val before = store.values.toMap()
        store.gateKey = "prefs.app_language"; store.release = CompletableDeferred()
        val load = async { repo.load() }; runCurrent()
        assertTrue(store.entered.isCompleted); assertEquals(SettingsLoadState.Loading, repo.loadState.value)
        load.cancelAndJoin()
        assertEquals(SettingsLoadState.Ready, repo.loadState.value)
        assertEquals(settings, repo.settings.value); assertEquals(prefs, repo.preferences.value)
        assertEquals(before, store.values)
    }

    @Test
    fun concurrent_loads_wait_for_one_complete_snapshot_instead_of_interleaving_startup_migrations() = runTest {
        val store = FixtureStore(); store.gateKey = "ai.endpoint_profiles_json"; store.release = CompletableDeferred()
        val repo = SettingsRepository(store)
        val jobs = List(3) { async { repo.load() } }; runCurrent()
        assertEquals(1, store.reads)
        assertEquals(SettingsLoadState.Loading, repo.loadState.value)
        assertTrue(store.values.isEmpty())
        store.release!!.complete(Unit); jobs.forEach { it.await() }
        assertEquals(SettingsLoadState.Ready, repo.loadState.value)
        assertEquals(1, repo.settings.value.profiles.size)
    }

    @Test
    fun provider_cancellation_is_not_wrapped_as_storage_unavailability() = runTest {
        val store = FixtureStore(); store.failKey = "ai.endpoint_profiles_json"; store.failure = CancellationException("synthetic")
        val repo = SettingsRepository(store)
        assertFailsWith<CancellationException> { repo.load() }
        assertEquals(SettingsLoadState.NotLoaded, repo.loadState.value)
        assertTrue(store.values.isEmpty())
    }

    @Test
    fun explicit_api_key_clear_preserves_voice_and_identity_entries() = runTest {
        val store = FixtureStore(); val repo = SettingsRepository(store); repo.load()
        store.values[SecureKeys.AI_API_KEY] = "synthetic-api"
        store.values[SecureKeys.apiKey(ApiModelResolver.DEFAULT_PROFILE_ID)] = "synthetic-profile-api"
        store.values[SecureKeys.VOICE_PROFILE_AES_KEY] = "synthetic-voice"
        store.values[SecureKeys.DEVICE_BIND_TOKEN] = "synthetic-identity"
        val before = store.values.toMap()
        repo.clearAllApiKeys()
        assertEquals(before.filterKeys { !it.startsWith("ai.api_key") }, store.values)
        assertFalse(repo.settings.value.hasApiKey)
    }

    @Test
    fun api_key_clear_removes_keys_of_profiles_that_were_never_loaded() = runTest {
        val store = FixtureStore()
        store.values[SecureKeys.AI_API_KEY] = "synthetic-legacy"
        store.values[SecureKeys.apiKey("never-listed")] = "synthetic-unlisted"
        store.values[SecureKeys.VOICE_PROFILE_AES_KEY] = "synthetic-voice"
        // Reads fail like a locked keystore, so settings never load and no profile list exists.
        store.failKey = "ai.endpoint_profiles_json"
        val repo = SettingsRepository(store)
        assertFailsWith<SecureStoreUnavailableException> { repo.load() }
        repo.clearAllApiKeys()
        assertEquals(mapOf(SecureKeys.VOICE_PROFILE_AES_KEY to "synthetic-voice"), store.values)
    }

    private class FixtureStore : SecureStore {
        val values = linkedMapOf<String, String>()
        var failKey: String? = null
        var failure: Exception = SecureStoreUnavailableException(IllegalStateException("synthetic provider"))
        var gateKey: String? = null
        var release: CompletableDeferred<Unit>? = null
        val entered = CompletableDeferred<Unit>()
        var reads = 0
        override suspend fun getString(key: String): String? {
            reads++
            if (key == gateKey) { entered.complete(Unit); release!!.await() }
            if (key == failKey) throw failure
            return values[key]
        }
        override suspend fun putString(key: String, value: String) { values[key] = value }
        override suspend fun remove(key: String) { values.remove(key) }
        override suspend fun removeWithPrefix(prefix: String) { values.keys.removeAll { it.startsWith(prefix) } }
        override suspend fun contains(key: String) = values.containsKey(key)
    }
}
