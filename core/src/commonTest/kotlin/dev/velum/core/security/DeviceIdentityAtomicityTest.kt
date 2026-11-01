package dev.velum.core.security

import dev.velum.protocol.DeviceIdentityKeys
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalEncodingApi::class)
class DeviceIdentityAtomicityTest {
    private val old = identity("OLD", 1)
    private val next = identity("NEXT", 2)
    private val other = identity("OTHER", 3)

    @Test fun failure_at_each_previous_write_boundary_never_exposes_mixed_identity() = runTest {
        for (boundary in 1..5) {
            val store = legacyStore().apply { failBefore = boundary }
            val previous = store.values.toMap()
            val importer = SecureDeviceKeyImporter(store)
            try { importer.importFixture(next) } catch (_: SecureStoreUnavailableException) { /* expected injected failure */ }
            val loaded = importer.loadKeys()
            assertTrue(loaded == old || loaded == next, "Mixed identity at write boundary $boundary")
            for ((key, value) in previous) assertTrue(store.values[key] == value, "Legacy/sibling entry changed")
        }
    }

    @Test fun failed_update_before_commit_preserves_active_and_legacy_bytes() = runTest {
        val store = legacyStore(); val importer = SecureDeviceKeyImporter(store)
        importer.importFixture(next)
        val before = store.values.toMap(); store.failBefore = store.writes + 1
        assertFailsWith<SecureStoreUnavailableException> { importer.importFixture(other) }
        assertEquals(before, store.values)
        assertTrue(importer.loadKeys() == next)
    }

    @Test fun uncertain_commit_still_exposes_one_complete_identity_and_preserves_siblings() = runTest {
        val store = legacyStore().apply { failAfter = 1 }; val before = store.values.toMap()
        val importer = SecureDeviceKeyImporter(store)
        assertFailsWith<SecureStoreUnavailableException> { importer.importFixture(next) }
        assertTrue(importer.loadKeys() == next)
        for ((key,value) in before) assertTrue(store.values[key] == value, "Sibling or legacy bytes changed")
    }

    @Test fun reading_legacy_identity_does_not_migrate_or_change_stored_bytes() = runTest {
        val store = legacyStore(); val before = store.values.toMap(); val importer = SecureDeviceKeyImporter(store)
        assertTrue(importer.hasKeys()); assertTrue(importer.loadKeys() == old)
        assertEquals(before, store.values); assertEquals(0, store.writes); assertEquals(0, store.removes)
    }

    @Test fun fresh_import_works_without_creating_legacy_fields() = runTest {
        val store = Store(); val importer = SecureDeviceKeyImporter(store)
        importer.importFixture(next)
        assertTrue(importer.hasKeys()); assertTrue(importer.loadKeys() == next)
        assertEquals(setOf(SecureKeys.DEVICE_IDENTITY_BUNDLE), store.values.keys)
    }

    @Test fun unreadable_or_future_active_bundle_never_falls_back_to_legacy_or_mutates_it() = runTest {
        val valid = Store().also { SecureDeviceKeyImporter(it).importFixture(next) }.values.getValue(SecureKeys.DEVICE_IDENTITY_BUNDLE)
        for (raw in listOf("SYNTHETIC-CORRUPT-IDENTITY", "{}", valid.replace("\"version\":1", "\"version\":2"))) {
            val store = legacyStore().apply { values[SecureKeys.DEVICE_IDENTITY_BUNDLE] = raw }
            val before = store.values.toMap()
            assertNull(SecureDeviceKeyImporter(store).loadKeys())
            assertEquals(before, store.values)
        }
    }

    @Test fun provider_read_failure_propagates_without_legacy_fallback() = runTest {
        val store = legacyStore(); val fault = SecureStoreUnavailableException(IllegalStateException("Synthetic provider failure"))
        store.readFailure = fault
        assertSame(fault, assertFailsWith<SecureStoreUnavailableException> { SecureDeviceKeyImporter(store).loadKeys() })
        assertEquals(0, store.writes); assertEquals(0, store.removes)
    }

    @Test fun cancellation_before_commit_preserves_all_entries() = runTest {
        val store = legacyStore(); val before = store.values.toMap(); var cancelled = false
        val job = launch {
            coroutineContext.cancel()
            try { SecureDeviceKeyImporter(store).importFixture(next) }
            catch (_: CancellationException) { cancelled = true }
        }
        job.join()
        assertTrue(cancelled); assertEquals(before, store.values); assertEquals(0, store.writes)
    }

    @Test fun provider_cancellation_is_not_converted_to_failure_or_success() = runTest {
        val store = legacyStore(); val before = store.values.toMap(); val cancelled = CancellationException("Synthetic cancellation")
        store.beforeWrite = { throw cancelled }
        assertSame(cancelled, assertFailsWith<CancellationException> { SecureDeviceKeyImporter(store).importFixture(next) })
        assertEquals(before, store.values)
    }

    @Test fun overlapping_importers_and_reads_only_observe_complete_identities() = runTest {
        val store = legacyStore(); val firstReached = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val observed = mutableListOf<DeviceIdentityKeys?>()
        store.beforeWrite = { if (store.writes == 1) { firstReached.complete(Unit); release.await() } }
        store.afterWrite = { observed += SecureDeviceKeyImporter(store).loadKeys() }
        val first = launch { SecureDeviceKeyImporter(store).importFixture(next) }
        firstReached.await()
        val second = launch { SecureDeviceKeyImporter(store).importFixture(other) }
        second.join(); assertTrue(SecureDeviceKeyImporter(store).loadKeys() == other)
        release.complete(Unit); first.join()
        assertTrue(SecureDeviceKeyImporter(store).loadKeys() == next)
        assertTrue(observed.isNotEmpty())
        assertTrue(observed.all { it == old || it == next || it == other }, "Concurrent reader saw mixed identity")
    }

    @Test fun failed_explicit_clear_keeps_active_bundle_until_legacy_entries_are_removed() = runTest {
        val store = legacyStore(); val importer = SecureDeviceKeyImporter(store); importer.importFixture(next)
        store.removeFailureKey = SecureKeys.DEVICE_SERIAL_SIGNATURE
        assertFailsWith<SecureStoreUnavailableException> { importer.clearKeys() }
        assertTrue(importer.loadKeys() == next)
        store.removeFailureKey = null; importer.clearKeys()
        assertFalse(importer.hasKeys()); assertNull(importer.loadKeys())
        assertEquals(mapOf(SecureKeys.VOICE_PROFILE_AES_KEY to "SYNTHETIC-VOICE", SecureKeys.AI_API_KEY to "SYNTHETIC-API"),store.values)
    }

    private fun identity(label: String, byte: Byte) = DeviceIdentityKeys("SYNTHETIC-$label", ByteArray(256) { byte }, "SYNTHETIC-$label-PUBLIC", "SYNTHETIC-$label-PRIVATE", "SYNTHETIC-$label-TOKEN")
    private suspend fun SecureDeviceKeyImporter.importFixture(value: DeviceIdentityKeys) = importKeys(value.serialNumber, Base64.encode(value.serialSignature), value.bindToken, value.publicKeyPem, value.privateKeyPem)
    private fun legacyStore() = Store().apply {
        values[SecureKeys.DEVICE_SERIAL] = old.serialNumber; values[SecureKeys.DEVICE_SERIAL_SIGNATURE] = Base64.encode(old.serialSignature)
        values[SecureKeys.DEVICE_BIND_TOKEN] = old.bindToken; values[SecureKeys.DEVICE_PUBLIC_KEY_PEM] = old.publicKeyPem; values[SecureKeys.DEVICE_PRIVATE_KEY_PEM] = old.privateKeyPem
        values[SecureKeys.VOICE_PROFILE_AES_KEY] = "SYNTHETIC-VOICE"; values[SecureKeys.AI_API_KEY] = "SYNTHETIC-API"
    }
    private class Store : SecureStore {
        val values = linkedMapOf<String,String>()
        var writes = 0; var removes = 0
        var failBefore: Int? = null; var failAfter: Int? = null
        var beforeWrite: (suspend () -> Unit)? = null; var afterWrite: (suspend () -> Unit)? = null
        var readFailure: Exception? = null; var removeFailureKey: String? = null
        override suspend fun putString(key: String,value: String) {
            val index = ++writes; beforeWrite?.invoke()
            if (failBefore == index) throw SecureStoreUnavailableException(IllegalStateException("Synthetic precommit failure"))
            values[key] = value; afterWrite?.invoke()
            if (failAfter == index) throw SecureStoreUnavailableException(IllegalStateException("Synthetic uncertain commit"))
        }
        override suspend fun getString(key: String): String? { readFailure?.let { throw it }; return values[key] }
        override suspend fun contains(key: String) = key in values
        override suspend fun remove(key: String) {
            if (key == removeFailureKey) throw SecureStoreUnavailableException(IllegalStateException("Synthetic clear failure"))
            removes++; values.remove(key)
        }
    }
}
