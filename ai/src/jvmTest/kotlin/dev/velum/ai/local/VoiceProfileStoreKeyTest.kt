package dev.velum.ai.local

import dev.velum.core.security.InMemorySecureStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking

class VoiceProfileStoreKeyTest {
    @Test
    fun cipher_throws_until_ensureKey_primes_secure_store() {
        val dir = File.createTempFile("velum-vp-key", "").also { it.delete(); it.mkdirs() }
        val store = VoiceProfileStore(
            root = ModelStoreDir(dir.absolutePath),
            secureStore = InMemorySecureStore(),
        )
        assertFailsWith<IllegalStateException> {
            store.upsert("Alice", floatArrayOf(1f, 0f, 0f))
        }
        runBlocking { store.ensureKey() }
        assertNotNull(store.upsert("Alice", floatArrayOf(1f, 0f, 0f)))
    }

    @Test
    fun without_secure_store_file_key_still_works() {
        val dir = File.createTempFile("velum-vp-filekey", "").also { it.delete(); it.mkdirs() }
        val store = VoiceProfileStore(root = ModelStoreDir(dir.absolutePath))
        assertNotNull(store.upsert("Bob", floatArrayOf(0f, 1f, 0f)))
    }
}
