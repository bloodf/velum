package dev.velum.core.security

import dev.velum.protocol.identity.IdentityRedaction
import dev.velum.protocol.identity.SyntheticIdentityFixtures
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * #92 regression: synthetic identity import + AES-GCM persistence (JVM stand-in
 * for Android Keystore-backed [AndroidSecureStore]).
 *
 * Not covered on the JVM (requires device/emulator + AndroidKeyStore):
 * - Non-exportable Keystore key material (`SecretKey.encoded` unavailable)
 * - Private SharedPreferences surviving a real process/app restart
 * - Logcat capture proving no identity values in Android system logs
 */
@OptIn(ExperimentalEncodingApi::class)
class IdentityImportSecureStoreTest {

    @Test
    fun json_import_round_trip_persists_through_store_recreation() = runTest {
        val key = JvmAesGcmSecureStore.generateAes256Key()
        val backend = linkedMapOf<String, String>()
        val store = JvmAesGcmSecureStore(key = key, backend = backend)
        val importer = SecureDeviceKeyImporter(store)

        val raw = SyntheticIdentityFixtures.validBundleJson(includePems = true)
        val result = PlaudExportImporter.importInto(raw, importer)
        assertIs<PlaudExportParseResult.Success>(result)
        assertTrue(importer.hasKeys())

        // Simulate app restart: new store instance, same key + ciphertext backend.
        val reloaded = JvmAesGcmSecureStore(key = key, backend = backend)
        val reImporter = SecureDeviceKeyImporter(reloaded)
        assertTrue(reImporter.hasKeys())
        val loaded = assertNotNull(reImporter.loadKeys())
        assertEquals(SyntheticIdentityFixtures.SERIAL, loaded.serialNumber)
        assertEquals(SyntheticIdentityFixtures.BIND_TOKEN, loaded.bindToken)
        assertEquals(256, loaded.serialSignature.size)
        assertEquals(SyntheticIdentityFixtures.PUBLIC_KEY_PEM, loaded.publicKeyPem)
        assertEquals(SyntheticIdentityFixtures.PRIVATE_KEY_PEM, loaded.privateKeyPem)
    }

    @Test
    fun pasted_fields_import_succeeds() = runTest {
        val store = JvmAesGcmSecureStore()
        val importer = SecureDeviceKeyImporter(store)
        importer.importKeys(
            serialNumber = SyntheticIdentityFixtures.SERIAL,
            serialSignatureBase64 = SyntheticIdentityFixtures.signatureBase64(),
            bindToken = SyntheticIdentityFixtures.BIND_TOKEN,
            publicKeyPem = SyntheticIdentityFixtures.PUBLIC_KEY_PEM,
            privateKeyPem = "",
        )
        assertTrue(importer.hasKeys())
        val loaded = assertNotNull(importer.loadKeys())
        assertEquals(SyntheticIdentityFixtures.SERIAL, loaded.serialNumber)
        assertEquals(SyntheticIdentityFixtures.BIND_TOKEN, loaded.bindToken)
        assertEquals(SyntheticIdentityFixtures.PUBLIC_KEY_PEM, loaded.publicKeyPem)
        assertEquals("", loaded.privateKeyPem)
    }

    @Test
    fun overwrite_replaces_previous_identity() = runTest {
        val store = JvmAesGcmSecureStore()
        val importer = SecureDeviceKeyImporter(store)
        val firstSig = SyntheticIdentityFixtures.signatureBase64()
        importer.importKeys(
            serialNumber = SyntheticIdentityFixtures.SERIAL,
            serialSignatureBase64 = firstSig,
            bindToken = SyntheticIdentityFixtures.BIND_TOKEN,
            publicKeyPem = SyntheticIdentityFixtures.PUBLIC_KEY_PEM,
            privateKeyPem = SyntheticIdentityFixtures.PRIVATE_KEY_PEM,
        )

        val otherSerial = "SYNTH-SN-OVERWRITE"
        val otherToken = "synth-bind-token-overwrite"
        val otherSig = Base64.encode(ByteArray(256) { (it * 3).toByte() })
        importer.importKeys(
            serialNumber = otherSerial,
            serialSignatureBase64 = otherSig,
            bindToken = otherToken,
            publicKeyPem = "",
            privateKeyPem = "",
        )

        val loaded = assertNotNull(importer.loadKeys())
        assertEquals(otherSerial, loaded.serialNumber)
        assertEquals(otherToken, loaded.bindToken)
        assertEquals("", loaded.publicKeyPem)
        assertEquals("", loaded.privateKeyPem)
        assertFalse(store.contains(SecureKeys.DEVICE_PUBLIC_KEY_PEM))
        assertFalse(store.contains(SecureKeys.DEVICE_PRIVATE_KEY_PEM))
    }

    @Test
    fun wipe_clears_identity_and_vault() = runTest {
        val store = JvmAesGcmSecureStore()
        val importer = SecureDeviceKeyImporter(store)
        val raw = SyntheticIdentityFixtures.validBundleJson(includePems = true)
        assertIs<PlaudExportParseResult.Success>(PlaudExportImporter.importInto(raw, importer))
        assertTrue(importer.hasKeys())
        assertTrue(store.rawCiphertextSnapshot().isNotEmpty())

        importer.clearKeys()
        assertFalse(importer.hasKeys())
        assertNull(importer.loadKeys())
        assertFalse(store.contains(SecureKeys.DEVICE_SERIAL))
        assertFalse(store.contains(SecureKeys.DEVICE_BIND_TOKEN))

        // Re-import then full vault wipe (privacy wipe path).
        assertIs<PlaudExportParseResult.Success>(PlaudExportImporter.importInto(raw, importer))
        store.putString(SecureKeys.AI_API_KEY, "sk-test-not-real")
        store.wipe()
        assertFalse(importer.hasKeys())
        assertTrue(store.rawCiphertextSnapshot().isEmpty())
        assertNull(store.getString(SecureKeys.AI_API_KEY))
    }

    @Test
    fun corrupt_stored_blob_treated_as_missing() = runTest {
        val store = JvmAesGcmSecureStore()
        val importer = SecureDeviceKeyImporter(store)
        importer.importKeys(
            serialNumber = SyntheticIdentityFixtures.SERIAL,
            serialSignatureBase64 = SyntheticIdentityFixtures.signatureBase64(),
            bindToken = SyntheticIdentityFixtures.BIND_TOKEN,
        )
        assertTrue(importer.hasKeys())

        store.putRawCiphertextForTest(SecureKeys.DEVICE_BIND_TOKEN, "not-valid-aes-gcm-ciphertext!!!")
        assertNull(store.getString(SecureKeys.DEVICE_BIND_TOKEN))
        assertFalse(store.contains(SecureKeys.DEVICE_BIND_TOKEN))
        // Incomplete identity after corrupt bind token drop.
        assertFalse(importer.hasKeys())
        assertNull(importer.loadKeys())
    }

    @Test
    fun malformed_bundle_rejected_without_successful_import() = runTest {
        val store = JvmAesGcmSecureStore()
        val importer = SecureDeviceKeyImporter(store)
        val bundle = SyntheticIdentityFixtures.validBundle(includePems = true)
        val poisoned = SyntheticIdentityFixtures.validBundleJson(includePems = true)
            .replace("\"version\": 1", "\"version\": 99")

        val fail = assertIs<PlaudExportParseResult.Failure>(
            PlaudExportImporter.importInto(poisoned, importer),
        )
        assertTrue(fail.message.contains("99"), fail.message)
        assertFalse(importer.hasKeys())
        assertTrue(store.rawCiphertextSnapshot().isEmpty())
        assertFalse(IdentityRedaction.containsSecret(fail.message, bundle))
        assertFalse(IdentityRedaction.containsSecret(fail.toString(), bundle))
    }

    @Test
    fun malformed_pasted_signature_surfaced_without_leaking() = runTest {
        val store = JvmAesGcmSecureStore()
        val importer = SecureDeviceKeyImporter(store)
        val bad = "%%%not-base64%%%${SyntheticIdentityFixtures.BIND_TOKEN}%%%"
        val thrown = assertFailsWith<IllegalArgumentException> {
            importer.importKeys(
                serialNumber = SyntheticIdentityFixtures.SERIAL,
                serialSignatureBase64 = bad,
                bindToken = SyntheticIdentityFixtures.BIND_TOKEN,
            )
        }
        assertTrue(thrown.message!!.contains("malformed") || thrown.message!!.contains("snSignature"))
        assertFalse(thrown.message!!.contains(SyntheticIdentityFixtures.BIND_TOKEN))
        assertFalse(thrown.message!!.contains(bad))
        assertFalse(importer.hasKeys())
    }

    @Test
    fun backend_holds_ciphertext_not_plaintext_identity() = runTest {
        val store = JvmAesGcmSecureStore()
        val importer = SecureDeviceKeyImporter(store)
        val raw = SyntheticIdentityFixtures.validBundleJson(includePems = true)
        assertIs<PlaudExportParseResult.Success>(PlaudExportImporter.importInto(raw, importer))

        val snapshot = store.rawCiphertextSnapshot()
        assertTrue(snapshot.isNotEmpty())
        val joined = snapshot.values.joinToString("\n")
        val bundle = SyntheticIdentityFixtures.validBundle(includePems = true)
        for (secret in IdentityRedaction.secretSamples(bundle)) {
            assertFalse(
                joined.contains(secret),
                "persistence backend leaked a plaintext identity sample",
            )
        }
        // Ciphertext for the same plaintext must not be a stable empty/identity string.
        assertNotEquals(SyntheticIdentityFixtures.SERIAL, snapshot[SecureKeys.DEVICE_SERIAL])
        assertNotEquals(SyntheticIdentityFixtures.BIND_TOKEN, snapshot[SecureKeys.DEVICE_BIND_TOKEN])
    }

    @Test
    fun redaction_asserts_on_import_results_and_loaded_keys() = runTest {
        val store = JvmAesGcmSecureStore()
        val importer = SecureDeviceKeyImporter(store)
        val bundle = SyntheticIdentityFixtures.validBundle(includePems = true)
        val ok = assertIs<PlaudExportParseResult.Success>(
            PlaudExportImporter.importInto(
                SyntheticIdentityFixtures.validBundleJson(includePems = true),
                importer,
            ),
        )
        assertFalse(IdentityRedaction.containsSecret(ok.toString(), bundle))
        assertFalse(IdentityRedaction.containsSecret(ok.device.toString(), bundle))

        val loaded = assertNotNull(importer.loadKeys())
        assertFalse(IdentityRedaction.containsSecret(loaded.toString(), bundle))
    }

    @Test
    fun desktop_store_survives_file_reload_without_plaintext() = runTest {
        val dir = kotlin.io.path.createTempDirectory("velum-secure-test")
        val props = dir.resolve("secure.properties")
        val keyFile = dir.resolve("secure.aes256")
        try {
            val first = DesktopSecureStore(file = props, keyFile = keyFile)
            val importer = SecureDeviceKeyImporter(first)
            assertIs<PlaudExportParseResult.Success>(
                PlaudExportImporter.importInto(
                    SyntheticIdentityFixtures.validBundleJson(includePems = true),
                    importer,
                ),
            )

            val onDisk = props.toFile().readText()
            val bundle = SyntheticIdentityFixtures.validBundle(includePems = true)
            assertFalse(IdentityRedaction.containsSecret(onDisk, bundle))

            val second = DesktopSecureStore(file = props, keyFile = keyFile)
            val reloaded = SecureDeviceKeyImporter(second)
            assertTrue(reloaded.hasKeys())
            assertEquals(SyntheticIdentityFixtures.SERIAL, reloaded.loadKeys()?.serialNumber)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
