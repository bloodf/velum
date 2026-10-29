package dev.velum.ai.local

import dev.velum.core.security.InMemorySecureStore
import dev.velum.core.security.SecureRead
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
    fun legacy_constant_key_file_purged_and_provably_legacy_profile_dropped() {
        runBlocking {
            val dir = tempDir("velum-vp-legacy")
            val root = ModelStoreDir(dir.absolutePath)
            val voices = File(dir, "voices").also { it.mkdirs() }
            val profiles = File(voices, "profiles").also { it.mkdirs() }
            val constantKey = legacyConstantKeyBytesForTests()
            File(voices, ".profile_key").writeBytes(constantKey)

            val legacyCipher = createProfileCipher(constantKey)
            writeLegacyProfile(profiles, "legacy", floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f), legacyCipher)

            val store = VoiceProfileStore(root = root, secureStore = InMemorySecureStore())
            store.ensureKey()

            assertFalse(File(voices, ".profile_key").exists())
            assertTrue(File(voices, ".profile_key.purged").exists())
            assertFalse(File(profiles, "legacy.json").exists())
            assertFalse(File(profiles, "legacy.enc").exists())
            assertTrue(store.listProfiles().isEmpty())
            assertNotNull(store.upsert("Fresh", floatArrayOf(1f, 0f, 0f)))
            assertEquals(1, store.listProfiles().size)
        }
    }

    @Test
    fun purge_keeps_corrupt_missing_enc_and_dim_mismatch_profiles() {
        runBlocking {
            val dir = tempDir("velum-vp-purge-keep")
            val root = ModelStoreDir(dir.absolutePath)
            val voices = File(dir, "voices").also { it.mkdirs() }
            val profiles = File(voices, "profiles").also { it.mkdirs() }
            val constantKey = legacyConstantKeyBytesForTests()
            File(voices, ".profile_key").writeBytes(constantKey)
            val legacyCipher = createProfileCipher(constantKey)

            // Provably legacy — should be deleted.
            writeLegacyProfile(profiles, "drop-me", floatArrayOf(1f, 0f, 0f, 0f), legacyCipher)

            // Corrupt JSON — keep.
            File(profiles, "corrupt.json").writeText("{not-json")
            File(profiles, "corrupt.enc").writeBytes(ByteArray(48) { 1 })

            // Valid meta, missing .enc — keep.
            File(profiles, "noenc.json").writeText(
                Json.encodeToString(
                    LegacyMeta(id = "noenc", displayName = "NoEnc", dim = 4, encrypted = true),
                ),
            )

            // Decrypts under legacy key but dim in meta mismatches — keep.
            val emb = floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f)
            File(profiles, "baddim.json").writeText(
                Json.encodeToString(
                    LegacyMeta(id = "baddim", displayName = "BadDim", dim = 3, encrypted = true),
                ),
            )
            File(profiles, "baddim.enc").writeBytes(legacyCipher.encrypt(legacyPack(listOf(emb))))

            // Random ciphertext that does not open under the legacy key — keep.
            File(profiles, "garbage.json").writeText(
                Json.encodeToString(
                    LegacyMeta(id = "garbage", displayName = "Garbage", dim = 4, encrypted = true),
                ),
            )
            File(profiles, "garbage.enc").writeBytes(ByteArray(64) { 9 })

            val store = VoiceProfileStore(root = root, secureStore = InMemorySecureStore())
            store.ensureKey()

            assertFalse(File(profiles, "drop-me.json").exists())
            assertTrue(File(profiles, "corrupt.json").exists())
            assertTrue(File(profiles, "noenc.json").exists())
            assertTrue(File(profiles, "baddim.json").exists())
            assertTrue(File(profiles, "baddim.enc").exists())
            assertTrue(File(profiles, "garbage.json").exists())
            assertTrue(File(profiles, "garbage.enc").exists())
        }
    }

    @Test
    fun purged_marker_prevents_repeated_purge_when_legacy_file_remains() {
        runBlocking {
            val dir = tempDir("velum-vp-marker")
            val root = ModelStoreDir(dir.absolutePath)
            val voices = File(dir, "voices").also { it.mkdirs() }
            val profiles = File(voices, "profiles").also { it.mkdirs() }
            val constantKey = legacyConstantKeyBytesForTests()
            File(voices, ".profile_key").writeBytes(constantKey)
            val legacyCipher = createProfileCipher(constantKey)
            writeLegacyProfile(profiles, "first", floatArrayOf(1f, 0f, 0f, 0f), legacyCipher)

            VoiceProfileStore(root = root, secureStore = InMemorySecureStore()).ensureKey()
            assertTrue(File(voices, ".profile_key.purged").exists())
            assertFalse(File(profiles, "first.json").exists())

            // Simulate a failed legacy-file delete: file reappears with a new legacy blob.
            File(voices, ".profile_key").writeBytes(constantKey)
            writeLegacyProfile(profiles, "second", floatArrayOf(0f, 1f, 0f, 0f), legacyCipher)

            // Fresh SecureStore → mint again, but marker must block a second purge.
            VoiceProfileStore(root = root, secureStore = InMemorySecureStore()).ensureKey()
            assertTrue(File(profiles, "second.json").exists())
            assertTrue(File(profiles, "second.enc").exists())
        }
    }

    @Test
    fun wipeAll_removes_legacy_key_file_and_purged_marker() {
        runBlocking {
            val dir = tempDir("velum-vp-wipe-legacy")
            val root = ModelStoreDir(dir.absolutePath)
            val voices = File(dir, "voices").also { it.mkdirs() }
            File(voices, ".profile_key").writeBytes(legacyConstantKeyBytesForTests())
            File(voices, ".profile_key.purged").writeBytes(byteArrayOf(1))
            File(voices, "profiles").mkdirs()
            File(voices, "profiles/x.json").writeText("{}")

            val store = VoiceProfileStore(root = root, secureStore = InMemorySecureStore())
            store.wipeAll()

            assertFalse(File(voices, ".profile_key").exists())
            assertFalse(File(voices, ".profile_key.purged").exists())
            assertTrue(store.listProfiles().isEmpty())
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

    @Test
    fun android_like_transient_unreadable_neither_mints_nor_purges() {
        runBlocking {
            val dir = tempDir("velum-vp-android-like")
            val root = ModelStoreDir(dir.absolutePath)
            val secure = AndroidLikeSecureStore()
            val store = VoiceProfileStore(root = root, secureStore = secure)
            store.ensureKey()
            store.upsert("Keep", floatArrayOf(1f, 0f, 0f))
            val originalKey = secure.storedValue(VoiceProfileStore.PROFILE_KEY_SECURE)
            assertNotNull(originalKey)
            val putsAfterSeed = secure.putCount.get()

            // Transient decrypt failure: getString would delete (old Android), read is Unreadable.
            secure.markUnreadable(VoiceProfileStore.PROFILE_KEY_SECURE)
            val store2 = VoiceProfileStore(root = root, secureStore = secure)
            assertFailsWith<VoiceProfileKeyUnreadableException> { store2.ensureKey() }
            assertIs<VoiceKeyPrimingState.Failed>(store2.primingState.value)
            assertEquals(putsAfterSeed, secure.putCount.get())
            // read() left the entry intact (unlike destructive getString).
            assertTrue(secure.contains(VoiceProfileStore.PROFILE_KEY_SECURE))
            assertEquals(originalKey, secure.storedValue(VoiceProfileStore.PROFILE_KEY_SECURE))
            assertTrue(File(dir, "voices/profiles/keep.json").exists())
            assertTrue(File(dir, "voices/profiles/keep.enc").exists())

            secure.clearUnreadable(VoiceProfileStore.PROFILE_KEY_SECURE)
            val store3 = VoiceProfileStore(root = root, secureStore = secure)
            store3.ensureKey()
            assertIs<VoiceKeyPrimingState.Ready>(store3.primingState.value)
            assertEquals(1, store3.listProfiles().size)
            assertEquals(originalKey, secure.storedValue(VoiceProfileStore.PROFILE_KEY_SECURE))
            assertEquals(putsAfterSeed, secure.putCount.get())
        }
    }

    @Test
    fun android_like_getString_deletes_on_failure_but_read_does_not() {
        runBlocking {
            val secure = AndroidLikeSecureStore()
            secure.putString("k", "secret-value")
            secure.markUnreadable("k")
            assertIs<SecureRead.Unreadable>(secure.read("k"))
            assertTrue(secure.contains("k"))
            assertEquals(null, secure.getString("k"))
            assertFalse(secure.contains("k"))
        }
    }

    @Test
    fun value_path_does_not_mint() {
        runBlocking {
            val dir = tempDir("velum-vp-value")
            val secure = CountingSecureStore()
            val store = VoiceProfileStore(
                root = ModelStoreDir(dir.absolutePath),
                secureStore = secure,
            )
            store.ensureKey()
            assertEquals(1, secure.putCount.get())
            val key = secure.memSnapshot(VoiceProfileStore.PROFILE_KEY_SECURE)
            assertNotNull(key)

            val store2 = VoiceProfileStore(
                root = ModelStoreDir(dir.absolutePath),
                secureStore = secure,
            )
            store2.ensureKey()
            assertEquals(1, secure.putCount.get())
            assertEquals(key, secure.memSnapshot(VoiceProfileStore.PROFILE_KEY_SECURE))
            assertIs<VoiceKeyPrimingState.Ready>(store2.primingState.value)
        }
    }

    @Test
    fun matchOrNull_and_cacheOrSkip_degrade_on_priming_failure() {
        runBlocking {
            val dir = tempDir("velum-vp-degrade")
            val failing = object : SecureStore {
                override suspend fun getString(key: String): String? = error("keystore down")
                override suspend fun read(key: String): SecureRead = error("keystore down")
                override suspend fun putString(key: String, value: String) = error("keystore down")
                override suspend fun remove(key: String) = Unit
                override suspend fun contains(key: String): Boolean = false
                override suspend fun wipe() = Unit
            }
            val store = VoiceProfileStore(
                root = ModelStoreDir(dir.absolutePath),
                secureStore = failing,
            )
            // Must not throw — diarization degrades to unlabeled speakers.
            assertEquals(null, store.matchOrNull(floatArrayOf(1f, 0f, 0f)))
            store.cacheClusterEmbeddingOrSkip("rec", "S1", floatArrayOf(1f, 0f, 0f))
            assertIs<VoiceKeyPrimingState.Failed>(store.primingState.value)
        }
    }

    @Test
    fun without_legacy_key_file_undecryptable_profile_is_not_deleted() {
        runBlocking {
            val dir = tempDir("velum-vp-no-legacy")
            val root = ModelStoreDir(dir.absolutePath)
            val store = VoiceProfileStore(root = root, secureStore = InMemorySecureStore())
            store.ensureKey()
            store.upsert("Good", floatArrayOf(1f, 0f, 0f))

            val profiles = File(dir, "voices/profiles").also { it.mkdirs() }
            File(profiles, "garbage.json").writeText(
                Json.encodeToString(
                    LegacyMeta(
                        id = "garbage",
                        displayName = "Garbage",
                        dim = 3,
                        encrypted = true,
                    ),
                ),
            )
            File(profiles, "garbage.enc").writeBytes(ByteArray(48) { 7 })

            // Re-prime: no legacy file → purge must not run.
            store.ensureKey()
            assertTrue(File(profiles, "garbage.json").exists())
            assertTrue(File(profiles, "garbage.enc").exists())
            assertEquals(1, store.listProfiles().size)
            assertEquals("Good", store.listProfiles().single().displayName)
        }
    }

    private fun tempDir(prefix: String): File =
        File.createTempFile(prefix, "").also { it.delete(); it.mkdirs() }

    private fun writeLegacyProfile(
        profiles: File,
        id: String,
        emb: FloatArray,
        legacyCipher: ProfileCipher,
    ) {
        File(profiles, "$id.json").writeText(
            Json.encodeToString(
                LegacyMeta(
                    id = id,
                    displayName = id.replaceFirstChar { it.uppercase() },
                    dim = emb.size,
                    encrypted = true,
                ),
            ),
        )
        File(profiles, "$id.enc").writeBytes(legacyCipher.encrypt(legacyPack(listOf(emb))))
    }

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
    override suspend fun read(key: String): SecureRead {
        delay(delayMs)
        return mem.read(key)
    }
    override suspend fun putString(key: String, value: String) = mem.putString(key, value)
    override suspend fun remove(key: String) = mem.remove(key)
    override suspend fun contains(key: String): Boolean = mem.contains(key)
    override suspend fun wipe() = mem.wipe()
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
    override suspend fun read(key: String): SecureRead {
        if (failuresBeforeSuccess > 0) {
            failuresBeforeSuccess--
            error("keystore unavailable")
        }
        return mem.read(key)
    }
    override suspend fun putString(key: String, value: String) = mem.putString(key, value)
    override suspend fun remove(key: String) = mem.remove(key)
    override suspend fun contains(key: String): Boolean = mem.contains(key)
    override suspend fun wipe() = mem.wipe()
}

private class CountingSecureStore : SecureStore {
    private val mem = InMemorySecureStore()
    val putCount = AtomicInteger(0)
    var blockGetUntil: CompletableDeferred<Unit>? = null
    override suspend fun getString(key: String): String? {
        blockGetUntil?.await()
        return mem.getString(key)
    }
    override suspend fun read(key: String): SecureRead {
        blockGetUntil?.await()
        return mem.read(key)
    }
    override suspend fun putString(key: String, value: String) {
        putCount.incrementAndGet()
        mem.putString(key, value)
    }
    override suspend fun remove(key: String) = mem.remove(key)
    override suspend fun contains(key: String): Boolean = mem.contains(key)
    override suspend fun wipe() = mem.wipe()
    suspend fun memSnapshot(key: String): String? = mem.getString(key)
}

/**
 * Models pre-#92 Android [SecureStore.getString] (deletes the prefs entry on decrypt
 * failure and returns null) vs #92 [SecureStore.read] (returns [SecureRead.Unreadable]
 * without deleting). Priming must use [read] so a transient failure neither mints nor
 * wipes profiles.
 */
private class AndroidLikeSecureStore : SecureStore {
    private val map = mutableMapOf<String, String>()
    private val unreadable = mutableSetOf<String>()
    val putCount = AtomicInteger(0)

    fun markUnreadable(key: String) {
        unreadable += key
    }

    fun clearUnreadable(key: String) {
        unreadable -= key
    }

    fun storedValue(key: String): String? = map[key]

    fun restoreAfterDestructiveGetString(key: String, value: String) {
        map[key] = value
    }

    override suspend fun putString(key: String, value: String) {
        putCount.incrementAndGet()
        map[key] = value
    }

    override suspend fun getString(key: String): String? {
        if (key in unreadable) {
            map.remove(key)
            return null
        }
        return map[key]
    }

    override suspend fun read(key: String): SecureRead {
        if (!map.containsKey(key)) return SecureRead.Missing
        if (key in unreadable) {
            return SecureRead.Unreadable("javax.crypto.AEADBadTagException")
        }
        return SecureRead.Value(map.getValue(key))
    }

    override suspend fun remove(key: String) {
        map.remove(key)
    }

    override suspend fun contains(key: String): Boolean = map.containsKey(key)

    override suspend fun wipe() {
        map.clear()
    }
}
