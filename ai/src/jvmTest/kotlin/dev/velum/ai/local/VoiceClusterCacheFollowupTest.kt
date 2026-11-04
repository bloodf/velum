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

    @Test fun wipe_voice_data_works_without_a_key_and_with_a_corrupt_only_profile() = runTest {
        store(this).withReady { upsert("Synthetic Only", embedding, id = "only") }
        val enc = profiles.resolve("only.enc")
        enc.writeBytes(enc.readBytes().copyOf(4))
        cache.resolve("rec-9").createDirectories()
        cache.resolve("rec-9").resolve("speaker.enc").writeBytes(ByteArray(8))
        val noKey = store(this, InMemorySecureStore())
        assertFailsWith<VoiceProfileKeyUnavailableException> { noKey.withReady { inspectProfiles() } }
        noKey.wipeVoiceData()
        assertTrue(profiles.walkFiles().isEmpty(), "profile files survived the wipe")
        assertFalse(cache.exists() && cache.walkFiles().isNotEmpty(), "cached embeddings survived the wipe")
    }

    @Test fun one_corrupt_only_profile_is_listed_as_unreadable_when_the_key_is_right() = runTest {
        val store = store(this)
        store.withReady { upsert("Synthetic Only", embedding, id = "only") }
        val enc = profiles.resolve("only.enc")
        enc.writeBytes(enc.readBytes().copyOf(4))
        val inventory = store.withReady { inspectProfiles() } // does not throw: the key check file authenticates
        assertEquals(listOf("only"), inventory.unreadable.map { it.id })
        assertTrue(inventory.profiles.isEmpty())
    }

    @Test fun a_wrong_key_still_blocks_everything() = runTest {
        store(this).withReady { upsert("Synthetic Only", embedding, id = "only") }
        val wrongKey = store(this, InMemorySecureStore())
        assertFailsWith<VoiceProfileKeyUnavailableException> { wrongKey.withReady { inspectProfiles() } }
    }

    @Test fun a_corrupt_enc_keeps_the_plaintext() = runTest {
        val store = store(this)
        store.withReady { upsert("Synthetic Good", embedding, id = "good") }
        profiles.resolve("bad.json").writeBytes(
            """{"id":"bad","displayName":"Synthetic Bad","dim":3,"sampleCount":1,"encrypted":true}""".encodeToByteArray(),
        )
        profiles.resolve("bad.enc").writeBytes(ByteArray(4))
        profiles.resolve("bad.f32").writeBytes(VoiceProfileStore.floatsToBytes(embedding))
        assertEquals(0, store.migrateLegacyPlaintextProfiles())
        assertTrue(profiles.resolve("bad.f32").exists(), "the only readable copy of a profile was deleted")
    }

    @Test fun a_wrong_key_keeps_the_plaintext_and_the_call_throws() = runTest {
        store(this).withReady { upsert("Synthetic Good", embedding, id = "good") }
        profiles.resolve("legacy.json").writeBytes(
            """{"id":"legacy","displayName":"Synthetic Legacy","dim":3,"sampleCount":1,"encrypted":false}""".encodeToByteArray(),
        )
        profiles.resolve("legacy.f32").writeBytes(VoiceProfileStore.floatsToBytes(embedding))
        val wrongKey = store(this, InMemorySecureStore())
        assertFailsWith<VoiceProfileKeyUnavailableException> { wrongKey.migrateLegacyPlaintextProfiles() }
        assertTrue(profiles.resolve("legacy.f32").exists())
    }

    @Test fun naming_readiness_follows_the_key() = runTest {
        val secure = InMemorySecureStore()
        store(this, secure).withReady { upsert("Synthetic Good", embedding, id = "good") }
        assertTrue(store(this, secure).speakerNamingReady())
        assertFalse(store(this, InMemorySecureStore()).speakerNamingReady())
    }

    @Test fun the_diarization_result_carries_the_naming_outcome() {
        val span = dev.velum.ai.engine.SpeakerSpan("S1", 0, 10)
        val ok = VoiceProfileStore.SpeakerNaming.resultFor(listOf(span), VoiceProfileStore.SpeakerNaming(mapOf("S1" to "Synthetic"), false), "e", "m")
        assertEquals(mapOf("S1" to "Synthetic"), ok.namedSpeakers)
        assertFalse(ok.speakerNamesUnavailable)
        val unavailable = VoiceProfileStore.SpeakerNaming.resultFor(listOf(span), VoiceProfileStore.SpeakerNaming(emptyMap(), true), "e", "m")
        assertTrue(unavailable.speakerNamesUnavailable)
        assertFalse(VoiceProfileStore.SpeakerNaming.resultFor(listOf(span), null, "e", "m").speakerNamesUnavailable)
    }

    @Test fun a_durable_write_leaves_no_temp_file_and_replaces_the_old_one() {
        val dir = root.resolve("durable").also { it.createDirectories() }
        val target = dir.resolve("file.bin")
        target.writeBytes(byteArrayOf(1))
        val tmp = dir.resolve("file.bin.tmp")
        tmp.writeBytesDurably(byteArrayOf(2, 3))
        assertTrue(tmp.renameTo(target))
        dir.syncDirectory()
        assertContentEquals(byteArrayOf(2, 3), target.readBytes())
        assertTrue(dir.listChildren().map { it.name } == listOf("file.bin"), "stray files: ${dir.listChildren().map { it.name }}")
    }

    @Test fun wipe_removes_the_key_check_and_a_new_profile_can_be_enrolled_afterwards() = runTest {
        val secure = InMemorySecureStore()
        val store = store(this, secure)
        store.withReady { upsert("Synthetic Old", embedding, id = "old") }
        assertTrue(root.resolve("voices/.keycheck.enc").exists())
        store.wipeVoiceData()
        assertFalse(root.resolve("voices/.keycheck.enc").exists(), "a stale key check would make a later wrong key look right")
        store.withReady { upsert("Synthetic New", embedding, id = "new") }
        assertEquals(listOf("new"), store.withReady { inspectProfiles() }.profiles.map { it.id })
    }

    @Test fun a_key_that_vanished_mid_session_is_not_kept_in_memory_for_new_profiles() = runTest {
        val secure = InMemorySecureStore()
        val store = store(this, secure)
        store.withReady { upsert("Synthetic Old", embedding, id = "old") }
        secure.remove(VoiceProfileStore.PROFILE_KEY_SECURE) // the stored key is gone while the store still holds it
        store.wipeVoiceData()
        store.withReady { upsert("Synthetic New", embedding, id = "new") }
        // A fresh start (new store object, same secure store) must still read the profile that was just enrolled.
        val restarted = store(this, secure)
        assertEquals(listOf("new"), restarted.withReady { inspectProfiles() }.profiles.map { it.id })
    }
}
