package dev.velum.ai.local

import dev.velum.core.security.InMemorySecureStore
import dev.velum.core.security.SecureStore
import dev.velum.core.security.SecureStoreUnavailableException
import java.nio.file.Files
import java.security.ProviderException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalEncodingApi::class)
class VoiceProfileRecoveryTest {
    @Test
    fun valid_and_orphan_are_reported_without_writes_and_valid_profile_stays_usable() = fixture {
        seed("healthy", "Synthetic Healthy")
        orphan("orphan", "Synthetic Orphan")
        val before = snapshot(); val savedKey = memory.getString(VoiceProfileStore.PROFILE_KEY_SECURE)
        val report = store.withReady { inspectProfiles() }
        assertEquals(listOf("healthy"), report.profiles.map { it.id })
        assertEquals(VoiceProfileReadProblem.MissingEmbedding, report.unreadable.single().problem)
        assertTrue(report.unreadable.single().canRemove)
        assertEquals("healthy", store.withReady { match(embedding) }!!.profile.id)
        assertEquals("Synthetic Healthy", store.withReady { get("healthy") }!!.displayName)
        assertSnapshot(before)
        assertEquals(savedKey, memory.getString(VoiceProfileStore.PROFILE_KEY_SECURE))
    }

    @Test
    fun strict_export_and_targeted_upsert_refuse_silent_omission_or_overwriting_unreadable_files() = fixture {
        seed("healthy", "Synthetic Healthy"); orphan("orphan", "Synthetic Orphan")
        val before = snapshot()
        assertFailsWith<UnreadableVoiceProfileException> { store.withReady { exportEncrypted() } }
        assertFailsWith<UnreadableVoiceProfileException> { store.withReady { upsert("Replacement", embedding, id = "orphan") } }
        assertSnapshot(before)
        store.withReady { rename("healthy", "Synthetic Renamed") }
        assertEquals("Synthetic Renamed", store.withReady { get("healthy") }!!.displayName)
        assertContentEquals(before.getValue("orphan.json"), profiles.resolve("orphan.json").readBytes())
    }

    @Test
    fun explicit_removal_deletes_only_the_confirmed_orphan_and_preserves_key_and_healthy_bytes() = fixture {
        seed("healthy", "Synthetic Healthy"); orphan("orphan", "Synthetic Orphan")
        val before = snapshot(); val key = memory.getString(VoiceProfileStore.PROFILE_KEY_SECURE)
        val issue = store.withReady { inspectProfiles() }.unreadable.single()
        assertTrue(store.removeUnreadableProfile(issue))
        assertSnapshot(before - "orphan.json")
        assertEquals(key, memory.getString(VoiceProfileStore.PROFILE_KEY_SECURE))
        assertEquals("healthy", store.withReady { match(embedding) }!!.profile.id)
    }

    @Test
    fun encrypted_orphan_without_metadata_is_visible_and_kept_until_explicit_removal() = fixture {
        seed("healthy", "Synthetic Healthy"); seed("orphan", "Synthetic Orphan")
        profiles.resolve("orphan.json").deleteRecursively()
        val before = snapshot()
        val issue = store.withReady { inspectProfiles() }.unreadable.single()
        assertEquals(VoiceProfileReadProblem.MissingMetadata, issue.problem)
        assertSnapshot(before)
        assertTrue(store.removeUnreadableProfile(issue))
        assertSnapshot(before - "orphan.enc")
    }

    @Test
    fun malformed_metadata_does_not_hide_a_healthy_profile_or_destroy_its_encrypted_sibling() = fixture {
        seed("healthy", "Synthetic Healthy"); seed("broken", "Synthetic Broken")
        profiles.resolve("broken.json").writeBytes("{invalid synthetic json".encodeToByteArray())
        val before = snapshot()
        val report = store.withReady { inspectProfiles() }
        assertEquals("healthy", report.profiles.single().id)
        assertEquals(VoiceProfileReadProblem.InvalidMetadata, report.unreadable.single().problem)
        assertSnapshot(before)
    }

    @Test
    fun repaired_metadata_or_added_embedding_invalidates_the_old_removal_confirmation() = fixture {
        seed("healthy", "Synthetic Healthy"); seed("repair", "Synthetic Repair")
        val metadata = profiles.resolve("repair.json").readBytes()
        profiles.resolve("repair.json").deleteRecursively()
        val issue = store.withReady { inspectProfiles() }.unreadable.single()
        profiles.resolve("repair.json").writeBytes(metadata)
        val repaired = snapshot()
        assertFalse(store.removeUnreadableProfile(issue))
        assertSnapshot(repaired)
        assertEquals("Synthetic Repair", store.withReady { get("repair") }!!.displayName)
    }

    @Test
    fun an_added_companion_file_cannot_be_deleted_using_a_stale_orphan_token() = fixture {
        seed("repair", "Synthetic Repair")
        val encrypted = profiles.resolve("repair.enc").readBytes()
        profiles.resolve("repair.enc").deleteRecursively()
        val issue = store.withReady { inspectProfiles() }.unreadable.single()
        profiles.resolve("repair.enc").writeBytes(encrypted)
        val repaired = snapshot()
        assertFalse(store.removeUnreadableProfile(issue))
        assertSnapshot(repaired)
        assertTrue(store.withReady { inspectProfiles() }.unreadable.isEmpty())
    }

    @Test
    fun global_vault_failure_never_returns_a_per_file_corruption_report() = fixture {
        seed("healthy", "Synthetic Healthy"); val before = snapshot()
        val unavailable = object : SecureStore by memory {
            override suspend fun getString(key: String): String? = throw SecureStoreUnavailableException(ProviderException("synthetic"))
        }
        val other = VoiceProfileStore(root, unavailable, ioDispatcher = dispatcher)
        assertFailsWith<SecureStoreUnavailableException> { other.withReady { inspectProfiles() } }
        assertSnapshot(before)
    }

    @Test
    fun transient_cipher_provider_failure_propagates_and_retry_recovers_without_marking_files_bad() = fixture {
        seed("healthy", "Synthetic Healthy"); val before = snapshot()
        var unavailable = true
        val delegate = createProfileCipher(key)
        val cipher = object : ProfileCipher by delegate {
            override fun decrypt(cipher: ByteArray): ByteArray {
                if (unavailable) throw ProfileCipherUnavailableException(ProviderException("synthetic"))
                return delegate.decrypt(cipher)
            }
        }
        val other = VoiceProfileStore(root, memory, cipher, dispatcher)
        assertFailsWith<ProfileCipherUnavailableException> { other.withReady { inspectProfiles() } }
        assertSnapshot(before)
        unavailable = false
        assertEquals("healthy", other.withReady { inspectProfiles() }.profiles.single().id)
        assertTrue(other.withReady { inspectProfiles() }.unreadable.isEmpty())
        assertSnapshot(before)
    }

    @Test
    fun unknown_provider_error_and_cancellation_are_never_classified_as_file_damage() = fixture {
        seed("healthy", "Synthetic Healthy"); val before = snapshot()
        for (failure in listOf(ProviderException("synthetic provider"), CancellationException("synthetic cancellation"))) {
            val cipher = object : ProfileCipher by createProfileCipher(key) {
                override fun decrypt(cipher: ByteArray): ByteArray = throw failure
            }
            val other = VoiceProfileStore(root, memory, cipher, dispatcher)
            if (failure is CancellationException) {
                assertFailsWith<CancellationException> { other.withReady { inspectProfiles() } }
            } else {
                assertFailsWith<ProviderException> { other.withReady { inspectProfiles() } }
            }
            assertSnapshot(before)
        }
    }

    @Test
    fun wrong_but_well_formed_key_is_global_and_explicit_retry_rereads_a_restored_key() = fixture {
        seed("healthy", "Synthetic Healthy"); val before = snapshot()
        memory.putString(VoiceProfileStore.PROFILE_KEY_SECURE, Base64.encode(ByteArray(32) { 73 }))
        val other = VoiceProfileStore(root, memory, ioDispatcher = dispatcher)
        assertFailsWith<VoiceProfileKeyUnavailableException> { other.withReady { inspectProfiles() } }
        assertSnapshot(before)
        memory.putString(VoiceProfileStore.PROFILE_KEY_SECURE, Base64.encode(key)) // synthetic owner restore
        other.reloadKey()
        assertEquals("healthy", other.withReady { inspectProfiles() }.profiles.single().id)
        assertTrue(other.withReady { inspectProfiles() }.unreadable.isEmpty())
        assertSnapshot(before)
    }

    @Test
    fun a_single_unautheticated_blob_is_ambiguous_while_mixed_readable_data_is_reported_per_file() = fixture {
        seed("healthy", "Synthetic Healthy"); seed("unreadable", "Synthetic Unreadable")
        val blob = profiles.resolve("unreadable.enc").readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        profiles.resolve("unreadable.enc").writeBytes(blob)
        val before = snapshot()
        val report = store.withReady { inspectProfiles() }
        assertEquals("healthy", report.profiles.single().id)
        assertEquals(VoiceProfileReadProblem.CannotDecrypt, report.unreadable.single().problem)
        assertSnapshot(before)
        profiles.resolve("healthy.json").deleteRecursively(); profiles.resolve("healthy.enc").deleteRecursively()
        val single = snapshot()
        assertFailsWith<VoiceProfileKeyUnavailableException> { store.withReady { inspectProfiles() } }
        assertSnapshot(single)
    }

    @Test
    fun changed_backing_key_invalidates_a_decryption_failure_token_without_deleting_now_readable_data() = fixture {
        seed("healthy", "Synthetic Healthy"); seed("alternate", "Synthetic Alternate")
        val alternateKey = ByteArray(32) { (it + 23).toByte() }
        val packed = byteArrayOf(1, 0, 3, 0, 0, 0, 0, 0) + VoiceProfileStore.floatsToBytes(embedding)
        profiles.resolve("alternate.enc").writeBytes(createProfileCipher(alternateKey).encrypt(packed))
        val issue = store.withReady { inspectProfiles() }.unreadable.single()
        val before = snapshot()
        memory.putString(VoiceProfileStore.PROFILE_KEY_SECURE, Base64.encode(alternateKey))
        assertFalse(store.removeUnreadableProfile(issue))
        assertSnapshot(before)
        assertEquals("Synthetic Alternate", store.withReady { get("alternate") }!!.displayName)
    }

    @Test
    fun structural_orphan_removal_does_not_need_or_reset_a_temporarily_unavailable_vault() = fixture {
        seed("healthy", "Synthetic Healthy"); orphan("orphan", "Synthetic Orphan")
        val issue = store.withReady { inspectProfiles() }.unreadable.single()
        val before = snapshot()
        val unavailable = object : SecureStore by memory {
            override suspend fun getString(key: String): String? = error("Must not access the vault for explicit structural removal")
        }
        val other = VoiceProfileStore(root, unavailable, ioDispatcher = dispatcher)
        assertTrue(other.removeUnreadableProfile(issue))
        assertSnapshot(before - "orphan.json")
    }

    @Test
    fun cancellation_before_removal_and_foreign_root_tokens_cannot_delete_files() = fixture {
        seed("healthy", "Synthetic Healthy"); orphan("orphan", "Synthetic Orphan")
        val issue = store.withReady { inspectProfiles() }.unreadable.single(); val before = snapshot()
        kotlinx.coroutines.coroutineScope {
            val pending = async { store.removeUnreadableProfile(issue) }
            pending.cancelAndJoin()
        }
        assertSnapshot(before)
        val foreign = ModelStoreDir(Files.createTempDirectory("velum-voice-foreign-test").toString())
        try {
            val other = VoiceProfileStore(foreign, memory, ioDispatcher = dispatcher)
            assertFalse(other.removeUnreadableProfile(issue))
            assertSnapshot(before)
        } finally { foreign.deleteRecursively() }
    }

    @Test
    fun directory_shaped_like_a_profile_file_is_reported_but_never_recursively_removed() = fixture {
        seed("healthy", "Synthetic Healthy")
        profiles.resolve("directory.json").createDirectories()
        profiles.resolve("directory.json/keep.txt").writeBytes("synthetic keep".encodeToByteArray())
        val before = snapshot()
        val issue = store.withReady { inspectProfiles() }.unreadable.single()
        assertEquals(VoiceProfileReadProblem.FileUnavailable, issue.problem)
        assertFalse(issue.canRemove)
        assertFalse(store.removeUnreadableProfile(issue))
        assertSnapshot(before)
    }

    @Test
    fun unsafe_identifiers_cannot_escape_the_profile_directory() = fixture {
        seed("healthy", "Synthetic Healthy")
        val outside = root.resolve("outside.json"); outside.writeBytes("synthetic keep".encodeToByteArray())
        val before = snapshot()
        assertFailsWith<IllegalArgumentException> { store.withReady { upsert("Unsafe", embedding, id = "../outside") } }
        assertFailsWith<IllegalArgumentException> { store.delete("../outside") }
        assertContentEquals("synthetic keep".encodeToByteArray(), outside.readBytes())
        assertSnapshot(before)
    }

    @Test
    fun malformed_embedding_is_reported_without_losing_healthy_or_legacy_plaintext_profiles() = fixture {
        seed("healthy", "Synthetic Healthy")
        orphan("legacy", "Synthetic Legacy")
        profiles.resolve("legacy.f32").writeBytes(VoiceProfileStore.floatsToBytes(embedding))
        orphan("invalid", "Synthetic Invalid")
        profiles.resolve("invalid.f32").writeBytes(byteArrayOf(1, 2, 3))
        val before = snapshot()
        val report = store.withReady { inspectProfiles() }
        assertEquals(setOf("healthy", "legacy"), report.profiles.map { it.id }.toSet())
        assertEquals(VoiceProfileReadProblem.InvalidEmbedding, report.unreadable.single().problem)
        assertSnapshot(before)
    }

    @Test
    fun import_cannot_overwrite_an_unreadable_target_and_reload_never_replaces_a_missing_known_key() = fixture {
        seed("healthy", "Synthetic Healthy")
        val bundle = store.withReady { exportEncrypted() }
        profiles.resolve("healthy.enc").deleteRecursively()
        val before = snapshot()
        assertFailsWith<UnreadableVoiceProfileException> { store.withReady { importEncrypted(bundle) } }
        assertSnapshot(before)
        memory.remove(VoiceProfileStore.PROFILE_KEY_SECURE)
        assertFailsWith<VoiceProfileKeyUnavailableException> { store.reloadKey() }
        assertEquals(null, memory.getString(VoiceProfileStore.PROFILE_KEY_SECURE))
        assertSnapshot(before)
    }

    @Test
    fun key_reloaded_before_confirmation_and_a_different_store_invalidate_old_crypto_tokens() = fixture {
        seed("healthy", "Synthetic Healthy")
        seed("key-b", "Synthetic Key B"); seed("key-c", "Synthetic Key C")
        val keyB = ByteArray(32) { 41 }; val keyC = ByteArray(32) { 53 }
        val original = createProfileCipher(key)
        for ((id, replacement) in listOf("key-b" to keyB, "key-c" to keyC)) {
            val file = profiles.resolve("$id.enc")
            file.writeBytes(createProfileCipher(replacement).encrypt(original.decrypt(file.readBytes())))
        }
        val before = snapshot()
        val token = store.withReady { inspectProfiles() }.unreadable.single { it.id == "key-c" }
        memory.putString(VoiceProfileStore.PROFILE_KEY_SECURE, Base64.encode(keyB))
        store.reloadKey() // The cached key changed before Remove, not during its revalidation.
        assertFalse(store.removeUnreadableProfile(token))
        val other = VoiceProfileStore(root, memory, ioDispatcher = dispatcher)
        assertFalse(other.removeUnreadableProfile(token))
        assertSnapshot(before)
        assertEquals("key-b", store.withReady { inspectProfiles() }.profiles.single().id)
    }

    private fun fixture(block: suspend Fixture.() -> Unit) = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        try {
            f.memory.putString(VoiceProfileStore.PROFILE_KEY_SECURE, Base64.encode(f.key))
            f.block()
        } finally { f.root.deleteRecursively() }
    }

    private class Fixture(val dispatcher: CoroutineDispatcher) {
        val root = ModelStoreDir(Files.createTempDirectory("velum-voice-recovery-test").toString())
        val memory = InMemorySecureStore()
        val key = ByteArray(32) { (it + 7).toByte() }
        val store = VoiceProfileStore(root, memory, ioDispatcher = dispatcher)
        val profiles get() = root.resolve("voices/profiles")
        val embedding = floatArrayOf(1f, 0f, 0f)
        suspend fun seed(id: String, label: String) { store.withReady { upsert(label, embedding, id = id) } }
        fun orphan(id: String, label: String) {
            profiles.createDirectories()
            profiles.resolve("$id.json").writeBytes("""{"id":"$id","displayName":"$label","dim":3}""".encodeToByteArray())
        }
        fun snapshot() = profiles.walkFiles().associate { it.absolutePath.removePrefix(profiles.absolutePath + "/") to it.readBytes() }
        fun assertSnapshot(before: Map<String, ByteArray>) {
            val after = snapshot(); assertEquals(before.keys, after.keys)
            before.forEach { (name, bytes) -> assertContentEquals(bytes, after[name], name) }
        }
    }
}
