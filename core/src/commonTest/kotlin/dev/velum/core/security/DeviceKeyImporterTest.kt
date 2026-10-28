package dev.velum.core.security

import dev.velum.protocol.identity.IdentityRedaction
import dev.velum.protocol.identity.SyntheticIdentityFixtures
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalEncodingApi::class)
class DeviceKeyImporterTest {
    private val validSigB64: String = SyntheticIdentityFixtures.signatureBase64()

    @Test
    fun import_persistsAndClears() = runTest {
        val store = InMemorySecureStore()
        val importer = SecureDeviceKeyImporter(store)
        assertFalse(importer.hasKeys())

        importer.importKeys(
            serialNumber = SyntheticIdentityFixtures.SERIAL,
            serialSignatureBase64 = validSigB64,
            bindToken = SyntheticIdentityFixtures.BIND_TOKEN,
        )
        assertTrue(importer.hasKeys())
        val loaded = importer.loadKeys()
        assertEquals(SyntheticIdentityFixtures.SERIAL, loaded?.serialNumber)
        assertEquals(SyntheticIdentityFixtures.BIND_TOKEN, loaded?.bindToken)
        assertEquals(256, loaded?.serialSignature?.size)

        // Values live only under SecureKeys — never concatenate into a log string here.
        assertTrue(store.contains(SecureKeys.DEVICE_SERIAL))
        assertTrue(store.contains(SecureKeys.DEVICE_SERIAL_SIGNATURE))
        assertTrue(store.contains(SecureKeys.DEVICE_BIND_TOKEN))

        importer.clearKeys()
        assertFalse(importer.hasKeys())
        assertNull(importer.loadKeys())
        assertFalse(store.contains(SecureKeys.DEVICE_SERIAL))
        assertFalse(store.contains(SecureKeys.DEVICE_BIND_TOKEN))
    }

    @Test
    fun import_overwrite_and_store_wipe() = runTest {
        val store = InMemorySecureStore()
        val importer = SecureDeviceKeyImporter(store)
        importer.importKeys(
            serialNumber = SyntheticIdentityFixtures.SERIAL,
            serialSignatureBase64 = validSigB64,
            bindToken = SyntheticIdentityFixtures.BIND_TOKEN,
            publicKeyPem = SyntheticIdentityFixtures.PUBLIC_KEY_PEM,
        )
        val otherSig = Base64.encode(ByteArray(256) { 7 })
        importer.importKeys(
            serialNumber = "SYNTH-OTHER",
            serialSignatureBase64 = otherSig,
            bindToken = "other-token",
            publicKeyPem = "",
        )
        assertEquals("SYNTH-OTHER", importer.loadKeys()?.serialNumber)
        assertFalse(store.contains(SecureKeys.DEVICE_PUBLIC_KEY_PEM))

        store.wipe()
        assertFalse(importer.hasKeys())
        assertNull(store.getString(SecureKeys.DEVICE_SERIAL))
    }

    @Test
    fun import_rejectsMalformedSignature() = runTest {
        val importer = SecureDeviceKeyImporter(InMemorySecureStore())
        assertFailsWith<IllegalArgumentException> {
            importer.importKeys(
                serialNumber = "x",
                serialSignatureBase64 = Base64.encode(byteArrayOf(1, 2, 3)),
                bindToken = "t",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            importer.importKeys(
                serialNumber = " ",
                serialSignatureBase64 = validSigB64,
                bindToken = "t",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            importer.importKeys(
                serialNumber = "x",
                serialSignatureBase64 = validSigB64,
                bindToken = "  ",
            )
        }
        val bad = assertFailsWith<IllegalArgumentException> {
            importer.importKeys(
                serialNumber = SyntheticIdentityFixtures.SERIAL,
                serialSignatureBase64 = "!!!${SyntheticIdentityFixtures.BIND_TOKEN}!!!",
                bindToken = SyntheticIdentityFixtures.BIND_TOKEN,
            )
        }
        assertFalse(bad.message.orEmpty().contains(SyntheticIdentityFixtures.BIND_TOKEN))
        assertFalse(importer.hasKeys())
    }

    @Test
    fun loadKeys_toString_is_redacted() = runTest {
        val importer = SecureDeviceKeyImporter(InMemorySecureStore())
        importer.importKeys(
            serialNumber = SyntheticIdentityFixtures.SERIAL,
            serialSignatureBase64 = validSigB64,
            bindToken = SyntheticIdentityFixtures.BIND_TOKEN,
            publicKeyPem = SyntheticIdentityFixtures.PUBLIC_KEY_PEM,
            privateKeyPem = SyntheticIdentityFixtures.PRIVATE_KEY_PEM,
        )
        val loaded = importer.loadKeys()
        assertTrue(loaded != null)
        val bundle = SyntheticIdentityFixtures.validBundle(includePems = true)
        assertFalse(IdentityRedaction.containsSecret(loaded!!.toString(), bundle))
    }
}
