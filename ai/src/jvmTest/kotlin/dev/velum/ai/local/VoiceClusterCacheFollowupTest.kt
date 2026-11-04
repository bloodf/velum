package dev.velum.ai.local

import dev.velum.core.security.InMemorySecureStore
import dev.velum.core.security.SecureStore
import dev.velum.core.security.SecureStoreUnavailableException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

/** #320 review follow-ups: key failures degrade diarization, cache writes are atomic, legacy profiles get encrypted. */
class VoiceClusterCacheFollowupTest {
    private val root = ModelStoreDir(Files.createTempDirectory("velum-voice-followup-test").toString())
    private val embedding = floatArrayOf(0.25f, -1f, 3f)
    private val cache = root.resolve("voices/cluster-cache")
    private val profiles = root.resolve("voices/profiles")

    @AfterTest fun cleanup() = root.deleteRecursively()

    private fun store(scope: kotlinx.coroutines.test.TestScope, secure: SecureStore = InMemorySecureStore()) =
        VoiceProfileStore(root, secure, ioDispatcher = StandardTestDispatcher(scope.testScheduler))

    @Test fun a_missing_key_costs_only_the_names_and_the_cache() = runTest {
        // Encrypted data exists but its key is gone: ensureKey refuses to mint a new one.
        store(this).withReady { upsert("Synthetic Alice", embedding) }
        val lostKey = store(this, InMemorySecureStore())
        val named = lostKey.cacheAndNameSpeakers("rec-1", mapOf("S1" to embedding), matchThreshold = 0.5f)
        assertTrue(named.names.isEmpty())
        assertTrue(named.unavailable, "the caller must be told naming could not run")
        assertFalse(cache.resolve("rec-1").exists(), "nothing may be cached without a key")
        assertTrue(cache.walkFiles().none { it.name.endsWith(".f32") }, "no plaintext fallback")
    }

    @Test fun an_unavailable_secure_store_costs_only_the_names_and_the_cache() = runTest {
        val broken = object : SecureStore {
            override suspend fun putString(key: String, value: String) = throw SecureStoreUnavailableException(RuntimeException("synthetic"))
            override suspend fun getString(key: String): String? = throw SecureStoreUnavailableException(RuntimeException("synthetic"))
            override suspend fun remove(key: String) = Unit
            override suspend fun contains(key: String) = false
        }
        val named = store(this, broken).cacheAndNameSpeakers("rec-2", mapOf("S1" to embedding), matchThreshold = 0.5f)
        assertTrue(named.names.isEmpty())
        assertTrue(named.unavailable)
        assertFalse(cache.resolve("rec-2").exists())
    }

    @Test fun with_a_key_the_cache_is_written_and_matches_are_named() = runTest {
        val store = store(this)
        store.withReady { upsert("Synthetic Alice", embedding) }
        val named = store.cacheAndNameSpeakers("rec-3", mapOf("S1" to embedding), matchThreshold = 0.5f)
        assertEquals(mapOf("S1" to "Synthetic Alice"), named.names)
        assertFalse(named.unavailable)
        assertContentEquals(embedding, store.withReady { cachedClusterEmbedding("rec-3", "S1") })
    }

    @Test fun a_truncated_cache_file_is_a_miss_and_can_be_rewritten() = runTest {
        val store = store(this)
        store.withReady { cacheClusterEmbedding("rec-4", "speaker", embedding) }
        val file = cache.resolve("rec-4").resolve("speaker.enc")
        file.writeBytes(file.readBytes().copyOf(5)) // a crash left a truncated file
        assertNull(store.withReady { cachedClusterEmbedding("rec-4", "speaker") })
        store.withReady { cacheClusterEmbedding("rec-4", "speaker", embedding) }
        assertContentEquals(embedding, store.withReady { cachedClusterEmbedding("rec-4", "speaker") })
    }

    @Test fun cache_and_profile_writes_leave_no_temp_files() = runTest {
        val store = store(this)
        store.withReady {
            cacheClusterEmbedding("rec-5", "speaker", embedding)
            upsert("Synthetic Alice", embedding)
        }
        val leftovers = (cache.walkFiles() + profiles.walkFiles()).map { it.name }.filter { it.endsWith(".tmp") }
        assertTrue(leftovers.isEmpty(), "temp files left behind: $leftovers")
    }

    @Test fun legacy_plaintext_profiles_are_reencrypted_once_and_the_plaintext_removed() = runTest {
        val store = store(this)
        store.withReady { upsert("Synthetic Seed", embedding, id = "seed") } // creates the key and the directory
        profiles.resolve("legacy.json").writeBytes(
            """{"id":"legacy","displayName":"Synthetic Legacy","dim":3,"sampleCount":1,"encrypted":false}""".encodeToByteArray(),
        )
        profiles.resolve("legacy.f32").writeBytes(VoiceProfileStore.floatsToBytes(embedding))
        assertEquals(1, store.migrateLegacyPlaintextProfiles())
        assertFalse(profiles.resolve("legacy.f32").exists(), "plaintext embedding survived")
        assertTrue(profiles.resolve("legacy.enc").exists())
        val migrated = store.withReady { inspectProfiles() }.profiles.single { it.id == "legacy" }
        assertContentEquals(embedding, migrated.embedding)
        assertEquals(0, store.migrateLegacyPlaintextProfiles(), "a second run must find nothing to do")
    }

    @Test fun migration_creates_no_key_when_there_is_nothing_to_migrate() = runTest {
        val secure = InMemorySecureStore()
        assertEquals(0, store(this, secure).migrateLegacyPlaintextProfiles())
        assertNull(secure.getString(VoiceProfileStore.PROFILE_KEY_SECURE))
    }

    @Test fun without_the_key_the_recordings_older_cache_is_dropped_so_it_cannot_be_promoted_later() = runTest {
        val seeded = store(this)
        seeded.withReady { cacheClusterEmbedding("rec-6", "S1", embedding) }
        val cached = cache.resolve("rec-6")
        assertTrue(cached.exists())
        // A different, empty secure store: encrypted data exists but its key is gone.
        val lostKey = store(this, InMemorySecureStore())
        val outcome = lostKey.cacheAndNameSpeakers("rec-6", mapOf("S1" to floatArrayOf(9f, 9f, 9f)), matchThreshold = 0.5f)
        assertTrue(outcome.unavailable)
        assertFalse(cached.exists(), "the previous run's embeddings must not survive to be read as this run's speakers")
    }

    @Test fun a_leftover_plaintext_copy_next_to_an_authenticating_enc_is_removed_without_rewriting_the_enc() = runTest {
        val store = store(this)
        store.withReady { upsert("Synthetic Both", embedding, id = "both") }
        val enc = profiles.resolve("both.enc")
        val before = enc.readBytes()
        // A crash after the .enc was published and before the .f32 was deleted.
        profiles.resolve("both.f32").writeBytes(VoiceProfileStore.floatsToBytes(embedding))
        assertEquals(1, store.migrateLegacyPlaintextProfiles())
        assertFalse(profiles.resolve("both.f32").exists(), "plaintext survived next to the encrypted copy")
        assertContentEquals(before, enc.readBytes())
        assertEquals(0, store.migrateLegacyPlaintextProfiles())
    }

    @Test fun a_failed_write_throws_and_leaves_the_old_file_unchanged() = runTest {
        val store = store(this)
        store.withReady { cacheClusterEmbedding("rec-7", "speaker", embedding) }
        val dir = cache.resolve("rec-7")
        val enc = dir.resolve("speaker.enc")
        val before = enc.readBytes()
        dir.resolve("speaker.enc.tmp").createDirectories() // the temp name is taken, so the write cannot start
        assertFailsWith<Exception> { store.withReady { cacheClusterEmbedding("rec-7", "speaker", floatArrayOf(7f, 7f, 7f)) } }
        assertContentEquals(before, enc.readBytes())
    }

    @Test fun deleting_a_profile_and_the_startup_sweep_remove_temp_files_that_hold_plaintext_details() = runTest {
        val store = store(this)
        store.withReady { upsert("Synthetic Alice", embedding, id = "alice") }
        profiles.resolve("alice.json.tmp").writeBytes("name email notes".encodeToByteArray())
        profiles.resolve("alice.enc.tmp").writeBytes(ByteArray(4))
        store.delete("alice")
        assertTrue(profiles.listChildren().none { it.name.startsWith("alice") }, "files left: ${profiles.listChildren().map { it.name }}")

        profiles.resolve("bob.json.tmp").writeBytes("name".encodeToByteArray())
        cache.resolve("rec-8").createDirectories()
        cache.resolve("rec-8").resolve("speaker.enc.tmp").writeBytes(ByteArray(4))
        store.sweepTemporaryFiles()
        assertTrue((profiles.walkFiles() + cache.walkFiles()).none { it.name.endsWith(".tmp") })
    }
}
