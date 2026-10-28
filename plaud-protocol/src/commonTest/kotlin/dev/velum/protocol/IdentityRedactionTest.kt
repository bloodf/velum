package dev.velum.protocol

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Identity values must never appear in toString() (logs / exceptions / test output).
 */
class IdentityRedactionTest {
    @Test
    fun deviceIdentityKeys_toString_redactsSecrets() {
        val marker = "SYNTH-PEM-MARKER-NOT-A-REAL-KEY"
        val tokenMarker = "synth-bind-token-aaaa"
        val keys = DeviceIdentityKeys(
            serialNumber = "881SYNTH1",
            serialSignature = ByteArray(256) { (it % 251).toByte() },
            publicKeyPem = "-----BEGIN PUBLIC KEY-----\n$marker\n-----END PUBLIC KEY-----",
            privateKeyPem = "-----BEGIN PRIVATE KEY-----\n$marker\n-----END PRIVATE KEY-----",
            bindToken = tokenMarker,
        )
        val text = keys.toString()
        assertTrue("redacted" in text)
        assertFalse(marker in text)
        assertFalse(tokenMarker in text)
        assertFalse("881SYNTH1" in text)
        assertFalse(keys.serialSignature.contentToString() in text)
    }

    @Test
    fun sessionSecret_toString_redactsKeyMaterial() {
        val secret = Protocol20SessionSecret(
            key = ByteArray(32) { 7 },
            nonce = ByteArray(12) { 3 },
            aad = ByteArray(12) { 9 },
        )
        val text = secret.toString()
        assertTrue("redacted" in text)
        assertFalse(secret.key.contentToString() in text)
        assertFalse(secret.nonce.contentToString() in text)
        assertFalse(secret.aad.contentToString() in text)
    }
}
