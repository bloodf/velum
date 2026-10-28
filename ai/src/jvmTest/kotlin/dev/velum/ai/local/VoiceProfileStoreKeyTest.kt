package dev.velum.ai.local

import dev.velum.core.security.InMemorySecureStore
import dev.velum.core.security.SecureStore
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class VoiceProfileStoreKeyTest {

    @Test
    fun cipher_ops_wait_for_ensureKey_and_succeed() {
        runBlocking {
            val dir = tempDir("velum-vp-wait")
            val store = VoiceProfileStore(
                root = ModelStoreDir(dir.absolutePath),
                secureStore = SlowSecureStore(delayMs = 80),
            )
            // No prior ensureKey — upsert must await priming.
            assertNotNull(store.upsert("Alice", floatArrayOf(1f, 0f, 0f)))
            assertIs<VoiceKeyPrimingState.Ready>(store.primingState.value)
            assertEquals(1, store.listProfiles().size)
        }
    }

    @Test
    fun failed_priming_surfaces_once_and_retry_succeeds() {
        runBlocking {
            val dir = tempDir("velum-vp-fail")
            val secure = FlakySecureStore(failuresBeforeSuccess = 1)
            val store = VoiceProfileStore(
                root = ModelStoreDir(dir.absolutePath),
                secureStore = secure,
            )
            assertFailsWith<IllegalStateException> { store.ensureKey() }
            val failed = store.primingState.value
            assertIs<VoiceKeyPrimingState.Failed>(failed)
            assertEquals("IllegalStateException", failed.errorClassName)

            store.ensureKey()
            assertIs<VoiceKeyPrimingState.Ready>(store.primingState.value)
            assertNotNull(store.upsert("Bob", floatArrayOf(0f, 1f, 0f)))
        }
    }

    @Test
    fun concurrent_first_calls_prime_exactly_once() {
        runBlocking {
            val dir = tempDir("velum-vp-once")
            val secure = CountingSecureStore()
            val store = VoiceProfileStore(
                root = ModelStoreDir(dir.absolutePath),
                secureStore = secure,
            )
            val gate = CompletableDeferred<Unit>()
            secure.blockGetUntil = gate
            val jobs = List(8) {
                async {
                    store.upsert("P$it", floatArrayOf(1f, 0f, it.toFloat()))
                }
            }
            delay(40)
            gate.complete(Unit)
            jobs.awaitAll()
            assertEquals(1, secure.putCount.get())
            assertEquals(8, store.listProfiles().size)
        }
    }

    @Test
    fun legacy_constant_key_file_purged_and_undecryptable_profile_dropped() {
        runBlocking {
            val dir = tempDir("velum-vp-legacy")
            val root = ModelStoreDir(dir.absolutePath)
            val voices = File(dir, "voices").also { it.mkdirs() }
            val profiles = File(voices, "profiles").also { it.mkdirs() }
            val constantKey = legacyConstantKeyBytesForTests()
            File(voices, ".profile_key").writeBytes(constantKey)

            val legacyCipher = createProfileCipher(constantKey)
            val emb = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f)
            val packed = legacyPack(listOf(emb))
            File(profiles, "legacy.json").writeText(
                Json.encodeToString(
                    LegacyMeta(
                        id = "legacy",
                        displayName = "Legacy",
                        dim = emb.size,
                        encrypted = true,
                    ),
                ),
            )
            File(profiles, "legacy.enc").writeBytes(legacyCipher.encrypt(packed))

            val store = VoiceProfileStore(root = root, secureStore = InMemorySecureStore())
            store.ensureKey()

            assertFalse(File(voices, ".profile_key").exists())
            assertFalse(File(profiles, "legacy.json").exists())
            assertFalse(File(profiles, "legacy.enc").exists())
            assertTrue(store.listProfiles().isEmpty())
            assertNotNull(store.upsert("Fresh", floatArrayOf(1f, 0f, 0f)))
            assertEquals(1, store.listProfiles().size)
        }
    }

    @Test
    fun production_requires_secure_store_constructor() {
        // Compile-time: VoiceProfileStore(root, secureStore) — secureStore is non-null.
        val dir = tempDir("velum-vp-ctor")
        val store = VoiceProfileStore(
            root = ModelStoreDir(dir.absolutePath),
            secureStore = InMemorySecureStore(),
        )
        assertIs<VoiceKeyPrimingState.Unprimed>(store.primingState.value)
    }

    private fun tempDir(prefix: String): File =
        File.createTempFile(prefix, "").also { it.delete(); it.mkdirs() }

    private fun legacyPack(samples: List<FloatArray>): ByteArray {
        val dim = samples.first().size
        val header = ByteArray(8)
        header[0] = (samples.size and 0xFF).toByte()
        header[1] = ((samples.size ushr 8) and 0xFF).toByte()
        header[2] = (dim and 0xFF).toByte()
        header[3] = ((dim ushr 8) and 0xFF).toByte()
        val body = samples.flatMap { VoiceProfileStore.floatsToBytes(it).asIterable() }.toByteArray()
        return header + body
    }
}

@Serializable
private data class LegacyMeta(
    val id: String,
    val displayName: String,
    val dim: Int,
    val encrypted: Boolean = true,
)

private class SlowSecureStore(private val delayMs: Long) : SecureStore {
    private val mem = InMemorySecureStore()
    override suspend fun getString(key: String): String? {
        delay(delayMs)
        return mem.getString(key)
    }
    override suspend fun putString(key: String, value: String) = mem.putString(key, value)
    override suspend fun remove(key: String) = mem.remove(key)
    override suspend fun contains(key: String): Boolean = mem.contains(key)
}

private class FlakySecureStore(private var failuresBeforeSuccess: Int) : SecureStore {
    private val mem = InMemorySecureStore()
    override suspend fun getString(key: String): String? {
        if (failuresBeforeSuccess > 0) {
            failuresBeforeSuccess--
            error("keystore unavailable")
        }
        return mem.getString(key)
    }
    override suspend fun putString(key: String, value: String) = mem.putString(key, value)
    override suspend fun remove(key: String) = mem.remove(key)
    override suspend fun contains(key: String): Boolean = mem.contains(key)
}

private class CountingSecureStore : SecureStore {
    private val mem = InMemorySecureStore()
    val putCount = AtomicInteger(0)
    var blockGetUntil: CompletableDeferred<Unit>? = null
    override suspend fun getString(key: String): String? {
        blockGetUntil?.await()
        return mem.getString(key)
    }
    override suspend fun putString(key: String, value: String) {
        putCount.incrementAndGet()
        mem.putString(key, value)
    }
    override suspend fun remove(key: String) = mem.remove(key)
    override suspend fun contains(key: String): Boolean = mem.contains(key)
}
