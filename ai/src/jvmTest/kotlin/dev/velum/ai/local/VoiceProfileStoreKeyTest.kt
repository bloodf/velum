package dev.velum.ai.local

import dev.velum.core.security.InMemorySecureStore
import dev.velum.core.security.SecureStore
import java.nio.file.Files
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalEncodingApi::class, ExperimentalCoroutinesApi::class)
class VoiceProfileStoreKeyTest {
    private val embedding = floatArrayOf(1f, 0f, 0f)

    @Test
    fun first_access_awaits_key_without_blocking_the_caller() = runTest {
        val root = temporaryRoot()
        val backing = GatedSecureStore()
        val store = VoiceProfileStore(root, backing, ioDispatcher = StandardTestDispatcher(testScheduler))
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            store.withReady { upsert("Synthetic Alice", embedding) }
        }
        runCurrent()
        assertTrue(backing.readStarted.isCompleted)
        assertFalse(result.isCompleted)
        assertFalse(root.resolve("voices").exists(), "No partial metadata before readiness")
        backing.releaseRead.complete(Unit)
        assertEquals("Synthetic Alice", result.await().displayName)
        assertEquals(1, backing.writes)
        root.deleteRecursively()
    }

    @Test
    fun concurrent_initialization_across_instances_writes_one_key_and_keeps_profiles_readable() = runTest {
        val root = temporaryRoot()
        val backing = GatedSecureStore()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val stores = List(2) { VoiceProfileStore(root, backing, ioDispatcher = dispatcher) }
        val initializations = List(100) { i -> async { stores[i % 2].ensureKey() } }
        runCurrent()
        assertEquals(1, backing.reads, "Other callers must await the first read/create/write transaction")
        backing.releaseRead.complete(Unit)
        initializations.forEach { it.await() }
        assertEquals(1, backing.writes)
        stores[0].upsert("Synthetic Alice", embedding)
        assertContentEquals(embedding, stores[1].get("synthetic-alice")!!.embedding)
        repeat(3) { stores[it % 2].ensureKey() }
        assertEquals(1, backing.writes)
        root.deleteRecursively()
    }

    @Test
    fun cancelled_initializer_does_not_poison_readiness_and_a_waiter_can_finish() = runTest {
        val root = temporaryRoot()
        val backing = GatedSecureStore()
        val store = VoiceProfileStore(root, backing, ioDispatcher = StandardTestDispatcher(testScheduler))
        val first = async { store.ensureKey() }
        runCurrent()
        val next = async { store.withReady { upsert("Synthetic Alice", embedding) } }
        runCurrent()
        first.cancelAndJoin()
        backing.releaseRead.complete(Unit)
        assertNotNull(next.await())
        assertEquals(1, backing.writes)
        root.deleteRecursively()
    }

    @Test
    fun failed_write_is_not_published_and_retry_reads_a_write_that_committed_before_failure() = runTest {
        val root = temporaryRoot()
        val backing = object : SecureStore by InMemorySecureStore() {
            var persisted: String? = null
            var writes = 0
            override suspend fun getString(key: String): String? = persisted
            override suspend fun putString(key: String, value: String) {
                persisted = value
                writes++
                error("Synthetic storage acknowledgement failure")
            }
        }
        val store = VoiceProfileStore(root, backing, ioDispatcher = StandardTestDispatcher(testScheduler))
        assertFailsWith<IllegalStateException> { store.withReady { upsert("Synthetic Alice", embedding) } }
        assertFalse(root.resolve("voices").exists())
        store.withReady { upsert("Synthetic Alice", embedding) }
        assertEquals(1, backing.writes)
        assertNotNull(store.get("synthetic-alice"))
        root.deleteRecursively()
    }

    @Test
    fun unacknowledged_storage_write_never_exposes_a_key_or_writes_profile_data() = runTest {
        val root = temporaryRoot()
        val backing = object : SecureStore by InMemorySecureStore() {
            override suspend fun putString(key: String, value: String) = Unit
        }
        val store = VoiceProfileStore(root, backing, ioDispatcher = StandardTestDispatcher(testScheduler))
        assertFailsWith<IllegalStateException> { store.withReady { upsert("Synthetic Alice", embedding) } }
        assertFalse(root.resolve("voices").exists())
        root.deleteRecursively()
    }

    @Test
    fun existing_secure_key_is_not_rotated_and_reopened_profiles_remain_readable() = runTest {
        val root = temporaryRoot()
        val backing = InMemorySecureStore()
        val key = ByteArray(32) { (it + 7).toByte() }
        backing.putString(VoiceProfileStore.PROFILE_KEY_SECURE, Base64.encode(key))
        val dispatcher = StandardTestDispatcher(testScheduler)
        VoiceProfileStore(root, backing, ioDispatcher = dispatcher).withReady { upsert("Synthetic Alice", embedding) }
        val reopened = VoiceProfileStore(root, backing, ioDispatcher = dispatcher)
        assertContentEquals(embedding, reopened.withReady { get("synthetic-alice")!! }.embedding)
        assertEquals(Base64.encode(key), backing.getString(VoiceProfileStore.PROFILE_KEY_SECURE))
        root.deleteRecursively()
    }

    @Test
    fun malformed_blank_and_wrong_length_keys_fail_without_writes_or_partial_profiles() = runTest {
        for (encoded in listOf("%%%", "", " ", Base64.encode(ByteArray(16)))) {
            val root = temporaryRoot()
            val backing = InMemorySecureStore()
            backing.putString(VoiceProfileStore.PROFILE_KEY_SECURE, encoded)
            val store = VoiceProfileStore(root, backing, ioDispatcher = StandardTestDispatcher(testScheduler))
            assertFailsWith<IllegalStateException> { store.withReady { upsert("Synthetic Alice", embedding) } }
            assertEquals(encoded, backing.getString(VoiceProfileStore.PROFILE_KEY_SECURE))
            assertFalse(root.resolve("voices").exists())
            root.deleteRecursively()
        }
    }

    @Test
    fun missing_secure_key_with_encrypted_profiles_or_cache_never_creates_a_replacement() = runTest {
        for (path in listOf("profiles/synthetic.enc", "cluster-cache/recording/speaker.enc")) {
            val root = temporaryRoot()
            val blob = root.resolve("voices").resolve(path)
            java.io.File(blob.absolutePath).parentFile.mkdirs()
            val bytes = byteArrayOf(1, 2, 3)
            blob.writeBytes(bytes)
            val backing = InMemorySecureStore()
            val store = VoiceProfileStore(root, backing, ioDispatcher = StandardTestDispatcher(testScheduler))
            assertFailsWith<IllegalStateException> { store.withReady { listProfiles() } }
            assertEquals(null, backing.getString(VoiceProfileStore.PROFILE_KEY_SECURE))
            assertContentEquals(bytes, blob.readBytes())
            root.deleteRecursively()
        }
    }

    @Test
    fun invalid_ciphertext_is_reported_instead_of_silently_hiding_a_profile_or_using_plaintext_cache() = runTest {
        val root = temporaryRoot()
        val backing = InMemorySecureStore()
        val store = VoiceProfileStore(root, backing, ioDispatcher = StandardTestDispatcher(testScheduler))
        store.withReady {
            upsert("Synthetic Alice", embedding)
            cacheClusterEmbedding("recording", "speaker", embedding)
        }
        val profile = root.resolve("voices/profiles/synthetic-alice.enc")
        val cache = root.resolve("voices/cluster-cache/recording/speaker.enc")
        profile.writeBytes(byteArrayOf(1, 2, 3))
        cache.writeBytes(byteArrayOf(1, 2, 3))
        assertFailsWith<IllegalStateException> { store.withReady { listProfiles() } }
        assertFailsWith<IllegalArgumentException> { store.withReady { cachedClusterEmbedding("recording", "speaker") } }
        assertContentEquals(byteArrayOf(1, 2, 3), profile.readBytes())
        root.deleteRecursively()
    }

    @Test
    fun synchronous_secure_access_before_readiness_fails_without_partial_metadata() {
        val root = temporaryRoot()
        val store = VoiceProfileStore(root, InMemorySecureStore())
        assertFailsWith<IllegalStateException> { store.upsert("Synthetic Alice", embedding) }
        assertFalse(root.resolve("voices").exists())
        root.deleteRecursively()
    }

    @Test
    fun legacy_file_key_remains_readable_but_new_file_keys_are_distinct() = runTest {
        val root = temporaryRoot()
        val legacyKey = ByteArray(32) { (it * 17 + 41).toByte() }
        root.resolve("voices").createDirectories()
        root.resolve("voices/.profile_key").writeBytes(legacyKey)
        val store = VoiceProfileStore(root)
        store.upsert("Synthetic Bob", embedding)
        assertNotNull(VoiceProfileStore(root).get("synthetic-bob"))
        assertContentEquals(legacyKey, root.resolve("voices/.profile_key").readBytes())
        val freshA = temporaryRoot()
        val freshB = temporaryRoot()
        VoiceProfileStore(freshA).ensureKey()
        VoiceProfileStore(freshB).ensureKey()
        assertFalse(freshA.resolve("voices/.profile_key").readBytes().contentEquals(freshB.resolve("voices/.profile_key").readBytes()))
        listOf(root, freshA, freshB).forEach { it.deleteRecursively() }
    }

    @Test
    fun missing_or_corrupt_file_key_with_existing_ciphertext_is_preserved() = runTest {
        for (key in listOf<ByteArray?>(null, byteArrayOf(1))) {
            val root = temporaryRoot()
            root.resolve("voices/profiles").createDirectories()
            val blob = root.resolve("voices/profiles/synthetic.enc")
            blob.writeBytes(byteArrayOf(2, 3))
            key?.let { root.resolve("voices/.profile_key").writeBytes(it) }
            assertFailsWith<IllegalStateException> { VoiceProfileStore(root).ensureKey() }
            assertContentEquals(byteArrayOf(2, 3), blob.readBytes())
            assertEquals(key != null, root.resolve("voices/.profile_key").exists())
            root.deleteRecursively()
        }
    }

    @Test
    fun frozen_legacy_aes_gcm_profile_reopens_with_secure_or_file_key_without_rewriting_any_bytes() = runTest {
        // Frozen wire fixture from the pre-fix format, independently sealed with javax.crypto:
        // 12-byte IV + AES-GCM ciphertext/tag; plaintext is the old8byte sample header + f32 LE.
        // The historical constant belongs only to this synthetic compatibility fixture.
        val legacyKey = ByteArray(32) { (it * 17 + 41).toByte() }
        val ciphertext = Base64.decode("AQIDBAUGBwgJCgsMHFENa3ZpmwS3snyh9jC/LIupnsNRJkDpZWSs0Y9loro5FMM/")
        for (useSecureStore in listOf(true, false)) {
            val root = temporaryRoot()
            root.resolve("voices/profiles").createDirectories()
            root.resolve("voices/profiles/legacy-synthetic.json").writeBytes(
                """{"id":"legacy-synthetic","displayName":"Synthetic Legacy","dim":3,"sampleCount":1,"encrypted":true}""".encodeToByteArray(),
            )
            root.resolve("voices/profiles/legacy-synthetic.enc").writeBytes(ciphertext)
            val backing = if (useSecureStore) InMemorySecureStore().also {
                it.putString(VoiceProfileStore.PROFILE_KEY_SECURE, Base64.encode(legacyKey))
            } else null
            if (!useSecureStore) root.resolve("voices/.profile_key").writeBytes(legacyKey)
            val before = root.walkFiles().associate { it.absolutePath to it.readBytes() }
            repeat(2) {
                val store = VoiceProfileStore(root, backing, ioDispatcher = StandardTestDispatcher(testScheduler))
                val profile = store.withReady { listProfiles().single() }
                assertEquals("Synthetic Legacy", profile.displayName)
                assertContentEquals(embedding, profile.embedding)
                assertEquals(1, profile.sampleCount)
                val after = root.walkFiles().associate { it.absolutePath to it.readBytes() }
                assertEquals(before.keys, after.keys)
                before.forEach { (path, bytes) -> assertContentEquals(bytes, after[path]) }
                if (useSecureStore) assertEquals(Base64.encode(legacyKey), backing!!.getString(VoiceProfileStore.PROFILE_KEY_SECURE))
            }
            root.deleteRecursively()
        }
    }

    private fun temporaryRoot() = ModelStoreDir(Files.createTempDirectory("velum-voice-key-test").toString())

    private class GatedSecureStore : SecureStore by InMemorySecureStore() {
        val readStarted = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        var reads = 0
        var writes = 0
        private var value: String? = null
        override suspend fun getString(key: String): String? {
            reads++
            readStarted.complete(Unit)
            releaseRead.await()
            return value
        }
        override suspend fun putString(key: String, value: String) {
            writes++
            this.value = value
        }
    }
}
