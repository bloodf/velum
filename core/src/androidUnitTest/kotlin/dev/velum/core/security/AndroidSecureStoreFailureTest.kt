package dev.velum.core.security

import java.nio.ByteBuffer
import java.security.InvalidKeyException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** No Android context/provider/alias is used: all keys and ciphertext are synthetic. */
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidSecureStoreFailureTest {
    @Test
    fun getEntry_failures_preserve_every_entry_and_alias_then_retry_decrypts_old_values() = runTest {
        for (failure in listOf(ProviderException("synthetic provider"), NullPointerException("invalid null input"), UnrecoverableKeyException("synthetic invalidation"))) {
            val backend = SyntheticBackend.seeded()
            val before = backend.entries.toMap()
            val key = backend.key
            val store = AndroidSecureStore(backend, StandardTestDispatcher(testScheduler))
            backend.failAt = Stage.LoadKey
            backend.failure = failure
            val thrown = assertFailsWith<SecureStoreUnavailableException> { store.getString(SecureKeys.AI_API_KEY) }
            assertSame(failure, generateSequence(thrown as Throwable) { it.cause }.last())
            backend.assertPreserved(before, key)
            backend.failAt = null
            assertEquals("synthetic-api", store.getString(SecureKeys.AI_API_KEY))
            assertEquals("synthetic-voice", store.getString(SecureKeys.VOICE_PROFILE_AES_KEY))
            backend.assertPreserved(before, key)
        }
    }

    @Test
    fun write_getEntry_failure_does_not_create_rotate_or_delete_anything() = runTest {
        val b = SyntheticBackend.seeded(); val before = b.entries.toMap(); val key = b.key
        b.failAt = Stage.LoadKey
        val store = AndroidSecureStore(b, StandardTestDispatcher(testScheduler))
        assertFailsWith<SecureStoreUnavailableException> { store.putString(SecureKeys.AI_API_KEY, "synthetic-replacement") }
        b.assertPreserved(before, key)
        assertEquals(0, b.encryptions)
    }

    @Test
    fun encryption_authentication_and_invalidation_failures_preserve_siblings_and_old_value() = runTest {
        for (failure in listOf(ProviderException("synthetic encrypt failure"), InvalidKeyException("synthetic authentication required"), UnrecoverableKeyException("synthetic permanently invalidated"))) {
            val b = SyntheticBackend.seeded(); val before = b.entries.toMap(); val key = b.key
            b.failAt = Stage.Encrypt; b.failure = failure
            val store = AndroidSecureStore(b, StandardTestDispatcher(testScheduler))
            assertFailsWith<SecureStoreUnavailableException> { store.putString(SecureKeys.AI_API_KEY, "synthetic-replacement") }
            b.assertPreserved(before, key)
            b.failAt = null
            assertEquals("synthetic-api", store.getString(SecureKeys.AI_API_KEY))
        }
    }

    @Test
    fun decrypt_authentication_tag_and_invalidation_failures_never_become_missing_credentials() = runTest {
        for (failure in listOf(AEADBadTagException("synthetic authentication tag"), InvalidKeyException("synthetic authentication required"), UnrecoverableKeyException("synthetic invalidation"))) {
            val b = SyntheticBackend.seeded(); val before = b.entries.toMap(); val key = b.key
            b.failAt = Stage.Decrypt; b.failure = failure
            val store = AndroidSecureStore(b, StandardTestDispatcher(testScheduler))
            assertFailsWith<SecureStoreUnavailableException> { store.getString(SecureKeys.AI_API_KEY) }
            b.assertPreserved(before, key)
            b.failAt = null
            assertEquals("synthetic-api", store.getString(SecureKeys.AI_API_KEY))
        }
    }

    @Test
    fun malformed_ciphertext_preserves_all_entries_before_any_provider_call() = runTest {
        for (bad in listOf("%%%", "", "AA==", Base64.getEncoder().encodeToString(byteArrayOf(32, 1)))) {
            val b = SyntheticBackend.seeded(); b.entries[SecureKeys.AI_API_KEY] = bad
            val before = b.entries.toMap(); val key = b.key
            val store = AndroidSecureStore(b, StandardTestDispatcher(testScheduler))
            assertFailsWith<SecureStoreUnavailableException> { store.getString(SecureKeys.AI_API_KEY) }
            b.assertPreserved(before, key)
            assertEquals(0, b.keyReads)
        }
    }

    @Test
    fun missing_alias_with_existing_entries_fails_read_and_write_without_creating_a_key() = runTest {
        val b = SyntheticBackend.seeded(); b.key = null
        val before = b.entries.toMap(); val store = AndroidSecureStore(b, StandardTestDispatcher(testScheduler))
        assertFailsWith<SecureStoreUnavailableException> { store.getString(SecureKeys.AI_API_KEY) }
        assertFailsWith<SecureStoreUnavailableException> { store.putString("synthetic-new-entry", "synthetic-value") }
        b.assertPreserved(before, null)
    }

    @Test
    fun absent_entry_reads_do_not_generate_keys_even_in_an_empty_vault() = runTest {
        val b = SyntheticBackend(); val store = AndroidSecureStore(b, StandardTestDispatcher(testScheduler))
        assertEquals(null, store.getString("missing"))
        assertFalse(store.contains("missing"))
        b.assertPreserved(emptyMap(), null)
        assertEquals(0, b.keyReads)
    }

    @Test
    fun fresh_write_creates_one_key_and_reopened_store_decrypts_the_same_wire_format() = runTest {
        val b = SyntheticBackend(); val dispatcher = StandardTestDispatcher(testScheduler)
        AndroidSecureStore(b, dispatcher).putString("synthetic-new-entry", "synthetic-new-value")
        assertEquals(1, b.creations)
        val before = b.entries.toMap(); val key = b.key
        assertEquals("synthetic-new-value", AndroidSecureStore(b, dispatcher).getString("synthetic-new-entry"))
        assertEquals(before, b.entries); assertSame(key, b.key)
    }

    @Test
    fun concurrent_instances_serialize_first_key_creation_and_preserve_all_writes() = runTest {
        val b = SyntheticBackend(); b.release = CompletableDeferred(); b.gateAt = Stage.Create
        val dispatcher = StandardTestDispatcher(testScheduler)
        val stores = List(2) { AndroidSecureStore(b, dispatcher) }
        val jobs = List(40) { i -> async { stores[i % 2].putString("synthetic-$i", "value-$i") } }
        runCurrent()
        assertTrue(b.entered.isCompleted); assertEquals(0, b.entries.size)
        b.release!!.complete(Unit)
        jobs.forEach { it.await() }
        assertEquals(1, b.creations); assertEquals(40, b.entries.size)
        repeat(40) { assertEquals("value-$it", stores[it % 2].getString("synthetic-$it")) }
    }

    @Test
    fun key_creation_failure_is_explicit_without_automatic_retry_or_fallback() = runTest {
        val b = SyntheticBackend(); b.failAt = Stage.Create
        val store = AndroidSecureStore(b, StandardTestDispatcher(testScheduler))
        assertFailsWith<SecureStoreUnavailableException> { store.putString("new", "synthetic") }
        b.assertPreserved(emptyMap(), null)
        assertEquals(1, b.creationAttempts)
        b.failAt = null
        store.putString("new", "synthetic")
        assertEquals(1, b.creations)
    }

    @Test
    fun ciphertext_write_failure_or_negative_acknowledgement_preserves_alias_and_siblings() = runTest {
        for (negativeAck in listOf(true, false)) {
            val b = SyntheticBackend.seeded(); val before = b.entries.toMap(); val key = b.key
            if (negativeAck) b.writeAcknowledged = false else b.failAt = Stage.Write
            val store = AndroidSecureStore(b, StandardTestDispatcher(testScheduler))
            assertFailsWith<SecureStoreUnavailableException> { store.putString(SecureKeys.AI_API_KEY, "synthetic-replacement") }
            b.assertPreserved(before, key)
            b.writeAcknowledged = true; b.failAt = null
            store.putString(SecureKeys.AI_API_KEY, "synthetic-replacement")
            assertEquals("synthetic-replacement", store.getString(SecureKeys.AI_API_KEY))
            assertEquals(before[SecureKeys.VOICE_PROFILE_AES_KEY], b.entries[SecureKeys.VOICE_PROFILE_AES_KEY])
        }
    }

    @Test
    fun preference_read_failure_is_reported_without_removing_values() = runTest {
        val b = SyntheticBackend.seeded(); val before = b.entries.toMap(); val key = b.key
        b.failAt = Stage.Read
        val store = AndroidSecureStore(b, StandardTestDispatcher(testScheduler))
        assertFailsWith<SecureStoreUnavailableException> { store.getString(SecureKeys.AI_API_KEY) }
        assertFailsWith<SecureStoreUnavailableException> { store.contains(SecureKeys.AI_API_KEY) }
        b.assertPreserved(before, key)
    }

    @Test
    fun cancellation_from_provider_is_propagated_without_wrapping_or_mutation() = runTest {
        val b = SyntheticBackend.seeded(); val before = b.entries.toMap(); val key = b.key
        b.failAt = Stage.LoadKey; b.failure = CancellationException("synthetic cancellation")
        assertFailsWith<CancellationException> { AndroidSecureStore(b, StandardTestDispatcher(testScheduler)).getString(SecureKeys.AI_API_KEY) }
        b.assertPreserved(before, key)
    }

    @Test
    fun cancelling_an_in_flight_encryption_never_persists_or_resets_any_entry() = runTest {
        val b = SyntheticBackend.seeded(); val before = b.entries.toMap(); val key = b.key
        b.gateAt = Stage.Encrypt; b.release = CompletableDeferred()
        val store = AndroidSecureStore(b, StandardTestDispatcher(testScheduler))
        val write = async { store.putString(SecureKeys.AI_API_KEY, "synthetic-replacement") }
        runCurrent(); assertTrue(b.entered.isCompleted)
        write.cancelAndJoin()
        b.assertPreserved(before, key)
    }

    @Test
    fun explicit_remove_deletes_only_the_requested_entry_without_needing_the_alias() = runTest {
        val b = SyntheticBackend.seeded(); val key = b.key; val before = b.entries.toMap()
        b.failAt = Stage.LoadKey // An explicit delete must not need decryption.
        val store = AndroidSecureStore(b, StandardTestDispatcher(testScheduler))
        store.remove(SecureKeys.AI_API_KEY)
        assertEquals(before - SecureKeys.AI_API_KEY, b.entries)
        assertSame(key, b.key); assertEquals(0, b.creations); assertEquals(1, b.removals)
    }

    @Test
    fun removing_by_prefix_deletes_only_that_family_without_needing_the_alias() = runTest {
        val b = SyntheticBackend.seeded(); val key = b.key
        b.entries["${SecureKeys.API_KEY_PREFIX}other"] = "x"
        b.entries["${SecureKeys.API_KEY_PREFIX}default"] = "y"
        val before = b.entries.toMap()
        b.failAt = Stage.LoadKey // Deleting must not need decryption, like a locked keystore.
        AndroidSecureStore(b, StandardTestDispatcher(testScheduler)).removeWithPrefix(SecureKeys.API_KEY_PREFIX)
        assertEquals(before.filterKeys { !it.startsWith(SecureKeys.API_KEY_PREFIX) }, b.entries)
        assertSame(key, b.key); assertEquals(0, b.creations)
    }

    @Test
    fun failed_prefix_removal_reports_failure_and_keeps_everything() = runTest {
        val b = SyntheticBackend.seeded(); val before = b.entries.toMap(); val key = b.key
        b.failAt = Stage.Remove
        assertFailsWith<SecureStoreUnavailableException> {
            AndroidSecureStore(b, StandardTestDispatcher(testScheduler)).removeWithPrefix(SecureKeys.API_KEY_PREFIX)
        }
        b.assertPreserved(before, key)
    }

    @Test
    fun failed_explicit_remove_keeps_all_siblings_and_reports_failure() = runTest {
        val b = SyntheticBackend.seeded(); val before = b.entries.toMap(); val key = b.key
        b.failAt = Stage.Remove
        assertFailsWith<SecureStoreUnavailableException> { AndroidSecureStore(b, StandardTestDispatcher(testScheduler)).remove(SecureKeys.AI_API_KEY) }
        b.assertPreserved(before, key)
    }

    @Test
    fun legacy_wire_fixture_decrypts_without_rewriting_credentials_or_key_material() = runTest {
        // Independently encode the old AndroidSecureStore wire layout (u8 IV length + IV + GCM body).
        val b = SyntheticBackend.seeded(); val before = b.entries.toMap(); val key = b.key
        val store = AndroidSecureStore(b, StandardTestDispatcher(testScheduler))
        for ((name, expected) in SyntheticBackend.legacyValues) assertEquals(expected, store.getString(name))
        b.assertPreserved(before, key)
        assertEquals(0, b.encryptions)
    }

    private enum class Stage { Read, Write, Remove, LoadKey, Create, Encrypt, Decrypt }

    private class SyntheticBackend : AndroidVaultBackend {
        val entries = linkedMapOf<String, String>()
        var key: SecretKey? = null
        var failAt: Stage? = null
        var failure: Exception = ProviderException("synthetic provider unavailable")
        var gateAt: Stage? = null
        val entered = CompletableDeferred<Unit>()
        var release: CompletableDeferred<Unit>? = null
        var creations = 0; var creationAttempts = 0; var removals = 0; var keyReads = 0; var encryptions = 0
        var writeAcknowledged = true
        private suspend fun stage(stage: Stage) {
            if (gateAt == stage) { entered.complete(Unit); release!!.await() }
            if (failAt == stage) throw failure
        }
        override suspend fun readCiphertext(name: String): String? { stage(Stage.Read); return entries[name] }
        override suspend fun hasEntries() = entries.isNotEmpty()
        override suspend fun writeCiphertext(name: String, value: String): Boolean {
            stage(Stage.Write)
            if (!writeAcknowledged) return false
            entries[name] = value; return true
        }
        override suspend fun removeCiphertext(name: String): Boolean { stage(Stage.Remove); entries.remove(name); removals++; return true }
        override suspend fun removeCiphertextsWithPrefix(prefix: String): Boolean { stage(Stage.Remove); entries.keys.removeAll { it.startsWith(prefix) }; removals++; return true }
        override suspend fun loadKey(): SecretKey? { keyReads++; stage(Stage.LoadKey); return key }
        override suspend fun createKey(): SecretKey {
            creationAttempts++; stage(Stage.Create)
            check(key == null); creations++
            return SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES").also { key = it }
        }
        override suspend fun encrypt(key: SecretKey, plain: ByteArray): VaultCiphertext {
            encryptions++; stage(Stage.Encrypt)
            val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key)
            return VaultCiphertext(c.iv, c.doFinal(plain))
        }
        override suspend fun decrypt(key: SecretKey, iv: ByteArray, body: ByteArray): ByteArray {
            stage(Stage.Decrypt)
            val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            return c.doFinal(body)
        }
        fun assertPreserved(before: Map<String, String>, originalKey: SecretKey?) {
            assertEquals(before, entries); assertSame(originalKey, key)
            assertEquals(0, creations); assertEquals(0, removals)
        }
        companion object {
            val legacyValues = mapOf(SecureKeys.AI_API_KEY to "synthetic-api", SecureKeys.VOICE_PROFILE_AES_KEY to "synthetic-voice", SecureKeys.DEVICE_BIND_TOKEN to "synthetic-identity")
            fun seeded() = SyntheticBackend().apply {
                val secret = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES"); key = secret
                legacyValues.forEach { (name, value) ->
                    val iv = ByteArray(12) { (it + 7).toByte() }
                    val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, secret, GCMParameterSpec(128, iv))
                    val body = c.doFinal(value.toByteArray())
                    entries[name] = Base64.getEncoder().encodeToString(byteArrayOf(12) + iv + body)
                }
            }
        }
    }
}
