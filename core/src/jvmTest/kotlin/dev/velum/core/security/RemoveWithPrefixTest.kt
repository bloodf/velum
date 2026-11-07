package dev.velum.core.security

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

/** The prefix removal every [SecureStore] must support, so API keys can be cleared without reading the profile list (#429). */
class RemoveWithPrefixTest {
    private suspend fun exercise(store: SecureStore) {
        store.putString("ai.api_key", "legacy")
        store.putString("ai.api_key.default", "a")
        store.putString("ai.api_key.other", "b")
        store.putString("ai.endpoint_profiles_json", "keep")
        store.putString("voice.profile_aes_key_b64", "keep")
        store.removeWithPrefix(SecureKeys.API_KEY_PREFIX)
        assertNull(store.getString("ai.api_key.default"))
        assertNull(store.getString("ai.api_key.other"))
        assertEquals("legacy", store.getString("ai.api_key"), "the legacy key has no trailing dot and is removed by name")
        assertEquals("keep", store.getString("ai.endpoint_profiles_json"))
        assertEquals("keep", store.getString("voice.profile_aes_key_b64"))
    }

    @Test fun in_memory_store() = runBlocking { exercise(InMemorySecureStore()) }

    @Test fun desktop_store_persists_the_removal() = runBlocking {
        val file = Files.createTempDirectory("velum-secure-prefix").resolve("secure.properties")
        try {
            exercise(DesktopSecureStore(file))
            assertNull(DesktopSecureStore(file).getString("ai.api_key.default"), "the removal was not flushed to disk")
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
