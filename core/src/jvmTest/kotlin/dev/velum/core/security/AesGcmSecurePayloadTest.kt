package dev.velum.core.security

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AesGcmSecurePayloadTest {

    @Test
    fun versioned_round_trip() {
        val key = JvmAesGcmSecureStore.generateAes256Key()
        val encoded = AesGcmSecurePayload.encrypt(key, "synth-plain".toByteArray(Charsets.UTF_8))
        val raw = Base64.getDecoder().decode(encoded)
        assertEquals(AesGcmSecurePayload.FORMAT_VERSION, raw[0])
        val opened = AesGcmSecurePayload.decryptResult(key, encoded)
        val ok = assertIs<AesGcmDecryptResult.Ok>(opened)
        assertEquals("synth-plain", ok.plaintext.toString(Charsets.UTF_8))
    }

    @Test
    fun legacy_unversioned_blob_still_decrypts() {
        val key = JvmAesGcmSecureStore.generateAes256Key()
        val legacy = AesGcmSecurePayload.encryptLegacyUnversioned(
            key,
            "synth-legacy".toByteArray(Charsets.UTF_8),
        )
        val raw = Base64.getDecoder().decode(legacy)
        // Legacy: first byte is IV length (12), not FORMAT_VERSION (1).
        assertEquals(AesGcmSecurePayload.IV_BYTES, raw[0].toInt() and 0xFF)
        assertNotEquals(AesGcmSecurePayload.FORMAT_VERSION.toInt() and 0xFF, raw[0].toInt() and 0xFF)
        val opened = AesGcmSecurePayload.decryptResult(key, legacy)
        val ok = assertIs<AesGcmDecryptResult.Ok>(opened)
        assertEquals("synth-legacy", ok.plaintext.toString(Charsets.UTF_8))
    }

    @Test
    fun wrong_key_is_cryptographic_failure_not_malformed() {
        val keyA = JvmAesGcmSecureStore.generateAes256Key()
        val keyB = JvmAesGcmSecureStore.generateAes256Key()
        val encoded = AesGcmSecurePayload.encrypt(keyA, "x".toByteArray())
        assertEquals(
            AesGcmDecryptResult.CryptographicFailure,
            AesGcmSecurePayload.decryptResult(keyB, encoded),
        )
    }

    @Test
    fun garbage_base64_is_structurally_malformed() {
        assertEquals(
            AesGcmDecryptResult.StructurallyMalformed,
            AesGcmSecurePayload.decryptResult(
                JvmAesGcmSecureStore.generateAes256Key(),
                "%%%not-base64%%%",
            ),
        )
        assertTrue(
            AesGcmSecurePayload.decryptResult(
                JvmAesGcmSecureStore.generateAes256Key(),
                Base64.getEncoder().encodeToString(byteArrayOf(0x7F)),
            ) is AesGcmDecryptResult.StructurallyMalformed,
        )
    }
}
