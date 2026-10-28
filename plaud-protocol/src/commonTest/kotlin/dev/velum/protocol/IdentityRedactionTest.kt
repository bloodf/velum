package dev.velum.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Identity values must never appear in toString() (logs / exceptions / test output).
 * Compatible with #89: fixed `[REDACTED]` tokens, never print identity values.
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
        assertEquals(
            "DeviceIdentityKeys(serialNumber=[REDACTED], serialSignature=[REDACTED], " +
                "publicKeyPem=[REDACTED], privateKeyPem=[REDACTED], bindToken=[REDACTED])",
            text,
        )
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
        assertEquals(
            "Protocol20SessionSecret(key=[REDACTED], nonce=[REDACTED], aad=[REDACTED])",
            text,
        )
        assertTrue("[REDACTED]" in text)
        assertFalse(secret.key.contentToString() in text)
        assertFalse(secret.nonce.contentToString() in text)
        assertFalse(secret.aad.contentToString() in text)
    }
}
