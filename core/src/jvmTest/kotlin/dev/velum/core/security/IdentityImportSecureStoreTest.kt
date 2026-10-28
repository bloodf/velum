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
        importer.importKeys(
            serialNumber = SyntheticIdentityFixtures.SERIAL,
            serialSignatureBase64 = SyntheticIdentityFixtures.signatureBase64(),
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
        val backend = linkedMapOf<String, String>()
        val store = JvmAesGcmSecureStore(backend = backend)
        val importer = SecureDeviceKeyImporter(store)
        val raw = SyntheticIdentityFixtures.validBundleJson(includePems = true)
        assertIs<PlaudExportParseResult.Success>(PlaudExportImporter.importInto(raw, importer))
        assertTrue(importer.hasKeys())
        assertTrue(backend.isNotEmpty())

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
        assertTrue(backend.isEmpty())
        assertNull(store.getString(SecureKeys.AI_API_KEY))
    }

    @Test
    fun corrupt_stored_blob_treated_as_missing() = runTest {
        val backend = linkedMapOf<String, String>()
        val store = JvmAesGcmSecureStore(backend = backend)
        val importer = SecureDeviceKeyImporter(store)
        importer.importKeys(
            serialNumber = SyntheticIdentityFixtures.SERIAL,
            serialSignatureBase64 = SyntheticIdentityFixtures.signatureBase64(),
            bindToken = SyntheticIdentityFixtures.BIND_TOKEN,
        )
        assertTrue(importer.hasKeys())

        backend[SecureKeys.DEVICE_BIND_TOKEN] = "not-valid-aes-gcm-ciphertext!!!"
        assertNull(store.getString(SecureKeys.DEVICE_BIND_TOKEN))
        assertFalse(store.contains(SecureKeys.DEVICE_BIND_TOKEN))
        // Incomplete identity after corrupt bind token drop.
        assertFalse(importer.hasKeys())
        assertNull(importer.loadKeys())
    }

    @Test
    fun wrong_key_keeps_ciphertext_entries() = runTest {
        val keyA = JvmAesGcmSecureStore.generateAes256Key()
        val keyB = JvmAesGcmSecureStore.generateAes256Key()
        val backend = linkedMapOf<String, String>()
        val writer = JvmAesGcmSecureStore(key = keyA, backend = backend)
        writer.putString(SecureKeys.DEVICE_SERIAL, SyntheticIdentityFixtures.SERIAL)
        writer.putString(SecureKeys.DEVICE_BIND_TOKEN, SyntheticIdentityFixtures.BIND_TOKEN)
        val sealedSerial = backend[SecureKeys.DEVICE_SERIAL]
        val sealedBind = backend[SecureKeys.DEVICE_BIND_TOKEN]
        assertNotNull(sealedSerial)
        assertNotNull(sealedBind)

        val wrongKeyStore = JvmAesGcmSecureStore(key = keyB, backend = backend)
        assertNull(wrongKeyStore.getString(SecureKeys.DEVICE_SERIAL))
        assertNull(wrongKeyStore.getString(SecureKeys.DEVICE_BIND_TOKEN))
        // Entries must remain so a restored key can open them.
        assertTrue(wrongKeyStore.contains(SecureKeys.DEVICE_SERIAL))
        assertTrue(wrongKeyStore.contains(SecureKeys.DEVICE_BIND_TOKEN))
        assertEquals(sealedSerial, backend[SecureKeys.DEVICE_SERIAL])
        assertEquals(sealedBind, backend[SecureKeys.DEVICE_BIND_TOKEN])

        val restored = JvmAesGcmSecureStore(key = keyA, backend = backend)
        assertEquals(SyntheticIdentityFixtures.SERIAL, restored.getString(SecureKeys.DEVICE_SERIAL))
        assertEquals(SyntheticIdentityFixtures.BIND_TOKEN, restored.getString(SecureKeys.DEVICE_BIND_TOKEN))
    }

    @Test
    fun malformed_bundle_rejected_without_successful_import() = runTest {
        val backend = linkedMapOf<String, String>()
        val store = JvmAesGcmSecureStore(backend = backend)
        val importer = SecureDeviceKeyImporter(store)
        val bundle = SyntheticIdentityFixtures.validBundle(includePems = true)
        val poisoned = SyntheticIdentityFixtures.validBundleJson(includePems = true)
            .replace("\"version\": 1", "\"version\": 99")

        val fail = assertIs<PlaudExportParseResult.Failure>(
            PlaudExportImporter.importInto(poisoned, importer),
        )
        assertTrue(fail.message.contains("99"))
        assertFalse(importer.hasKeys())
        assertTrue(backend.isEmpty())
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
        val backend = linkedMapOf<String, String>()
        val store = JvmAesGcmSecureStore(backend = backend)
        val importer = SecureDeviceKeyImporter(store)
        val raw = SyntheticIdentityFixtures.validBundleJson(includePems = true)
        assertIs<PlaudExportParseResult.Success>(PlaudExportImporter.importInto(raw, importer))

        assertTrue(backend.isNotEmpty())
        val joined = backend.values.joinToString("\n")
        val bundle = SyntheticIdentityFixtures.validBundle(includePems = true)
        for (secret in IdentityRedaction.secretSamples(bundle)) {
            assertFalse(joined.contains(secret))
        }
        assertNotEquals(SyntheticIdentityFixtures.SERIAL, backend[SecureKeys.DEVICE_SERIAL])
        assertNotEquals(SyntheticIdentityFixtures.BIND_TOKEN, backend[SecureKeys.DEVICE_BIND_TOKEN])
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

    @Test
    fun desktop_regenerated_key_does_not_wipe_ciphertext() = runTest {
        val dir = kotlin.io.path.createTempDirectory("velum-secure-wrong-key")
        val props = dir.resolve("secure.properties")
        val keyFile = dir.resolve("secure.aes256")
        try {
            val first = DesktopSecureStore(file = props, keyFile = keyFile)
            first.putString(SecureKeys.DEVICE_SERIAL, SyntheticIdentityFixtures.SERIAL)
            first.putString(SecureKeys.DEVICE_BIND_TOKEN, SyntheticIdentityFixtures.BIND_TOKEN)
            val onDiskBefore = props.toFile().readText()
            assertTrue(onDiskBefore.contains(SecureKeys.DEVICE_SERIAL))
            assertTrue(onDiskBefore.contains(SecureKeys.DEVICE_BIND_TOKEN))

            // Simulate lost/replaced key file while ciphertext remains.
            java.nio.file.Files.delete(keyFile)
            val second = DesktopSecureStore(file = props, keyFile = keyFile)
            assertNull(second.getString(SecureKeys.DEVICE_SERIAL))
            assertNull(second.getString(SecureKeys.DEVICE_BIND_TOKEN))
            assertTrue(second.contains(SecureKeys.DEVICE_SERIAL))
            assertTrue(second.contains(SecureKeys.DEVICE_BIND_TOKEN))

            val onDiskAfter = props.toFile().readText()
            assertTrue(onDiskAfter.contains(SecureKeys.DEVICE_SERIAL))
            assertTrue(onDiskAfter.contains(SecureKeys.DEVICE_BIND_TOKEN))
            val bundle = SyntheticIdentityFixtures.validBundle()
            assertFalse(IdentityRedaction.containsSecret(onDiskAfter, bundle))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun file_backed_wrong_key_new_entry_preserves_original_on_restore() = runTest {
        val dir = kotlin.io.path.createTempDirectory("velum-secure-roundtrip")
        val props = dir.resolve("secure.properties")
        val keyFile = dir.resolve("secure.aes256")
        try {
            val first = DesktopSecureStore(file = props, keyFile = keyFile)
            first.putString(SecureKeys.DEVICE_SERIAL, SyntheticIdentityFixtures.SERIAL)
            val originalKeyBytes = java.nio.file.Files.readAllBytes(keyFile)
            assertEquals(32, originalKeyBytes.size)

            // Force failed decrypt with a different key file.
            val otherKey = JvmAesGcmSecureStore.generateAes256Key()
            java.nio.file.Files.write(keyFile, otherKey.encoded)
            val wrong = DesktopSecureStore(file = props, keyFile = keyFile)
            assertNull(wrong.getString(SecureKeys.DEVICE_SERIAL))
            assertTrue(wrong.contains(SecureKeys.DEVICE_SERIAL))
            wrong.putString(SecureKeys.AI_API_KEY, "sk-test-not-real")

            // Restore original key — original entry must decrypt again.
            java.nio.file.Files.write(keyFile, originalKeyBytes)
            val restored = DesktopSecureStore(file = props, keyFile = keyFile)
            assertEquals(
                SyntheticIdentityFixtures.SERIAL,
                restored.getString(SecureKeys.DEVICE_SERIAL),
            )
            assertTrue(restored.contains(SecureKeys.AI_API_KEY))
            assertNull(restored.getString(SecureKeys.AI_API_KEY))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun desktop_wrong_length_key_file_generates_new_key_keeps_ciphertext() = runTest {
        val dir = kotlin.io.path.createTempDirectory("velum-secure-short-key")
        val props = dir.resolve("secure.properties")
        val keyFile = dir.resolve("secure.aes256")
        try {
            val first = DesktopSecureStore(file = props, keyFile = keyFile)
            first.putString(SecureKeys.DEVICE_SERIAL, SyntheticIdentityFixtures.SERIAL)
            first.putString(SecureKeys.DEVICE_BIND_TOKEN, SyntheticIdentityFixtures.BIND_TOKEN)

            // Truncated key (crash between create and write) — quarantine, don't overwrite.
            val badBytes = byteArrayOf(0x01, 0x02, 0x03)
            java.nio.file.Files.write(keyFile, badBytes)
            val second = DesktopSecureStore(file = props, keyFile = keyFile)
            assertEquals(32, java.nio.file.Files.readAllBytes(keyFile).size)
            assertNull(second.getString(SecureKeys.DEVICE_SERIAL))
            assertNull(second.getString(SecureKeys.DEVICE_BIND_TOKEN))
            assertTrue(second.contains(SecureKeys.DEVICE_SERIAL))
            assertTrue(second.contains(SecureKeys.DEVICE_BIND_TOKEN))

            val badFiles = java.nio.file.Files.list(dir).use { stream ->
                stream.filter { p ->
                    p.fileName.toString().startsWith("secure.aes256.bad-")
                }.toList()
            }
            assertEquals(1, badFiles.size)
            assertTrue(badBytes.contentEquals(java.nio.file.Files.readAllBytes(badFiles[0])))

            val onDisk = props.toFile().readText()
            assertTrue(onDisk.contains(SecureKeys.DEVICE_SERIAL))
            assertTrue(onDisk.contains(SecureKeys.DEVICE_BIND_TOKEN))
            val bundle = SyntheticIdentityFixtures.validBundle()
            assertFalse(IdentityRedaction.containsSecret(onDisk, bundle))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
