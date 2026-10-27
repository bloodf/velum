package dev.velum.core.security

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
    private val validSigB64: String =
        Base64.encode(ByteArray(256) { (it % 251).toByte() })

    @Test
    fun import_persistsAndClears() = runTest {
        val store = InMemorySecureStore()
        val importer = SecureDeviceKeyImporter(store)
        assertFalse(importer.hasKeys())

        importer.importKeys(
            serialNumber = "881TEST01",
            serialSignatureBase64 = validSigB64,
            bindToken = "bind-token-demo",
        )
        assertTrue(importer.hasKeys())
        val loaded = importer.loadKeys()
        assertEquals("881TEST01", loaded?.serialNumber)
        assertEquals("bind-token-demo", loaded?.bindToken)
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
        assertFalse(importer.hasKeys())
    }
}
