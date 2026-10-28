package dev.velum.core.security

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Outcome of opening an AES-GCM vault blob.
 *
 * [StructurallyMalformed] blobs are safe to delete. [CryptographicFailure] usually
 * means the wrong key (or bit-flip of the tag) — keep the entry so a restored key
 * can still open it.
 */
internal sealed class AesGcmDecryptResult {
    class Ok(val plaintext: ByteArray) : AesGcmDecryptResult()
    data object StructurallyMalformed : AesGcmDecryptResult()
    data object CryptographicFailure : AesGcmDecryptResult()
}

/**
 * Wire format shared with [AndroidSecureStore]:
 * `Base64( ivLength:1 || iv || ciphertext+tag )` using AES-256-GCM.
 *
 * JVM/desktop uses a software [SecretKey]; Android uses a non-exportable
 * Android Keystore key. Payload layout must stay identical so tests can
 * assert ciphertext-at-rest without Keystore.
 */
internal object AesGcmSecurePayload {
    const val TRANSFORMATION: String = "AES/GCM/NoPadding"
    const val GCM_TAG_BITS: Int = 128
    const val IV_BYTES: Int = 12
    private const val GCM_TAG_BYTES: Int = 16
    private val random = SecureRandom()

    fun encrypt(key: SecretKey, plaintext: ByteArray): String {
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext)
        val payload = ByteBuffer.allocate(1 + iv.size + ciphertext.size)
            .put(iv.size.toByte())
            .put(iv)
            .put(ciphertext)
            .array()
        return Base64.getEncoder().encodeToString(payload)
    }

    fun decryptResult(key: SecretKey, encoded: String): AesGcmDecryptResult {
        val payload = try {
            Base64.getDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            return AesGcmDecryptResult.StructurallyMalformed
        }
        if (payload.isEmpty()) return AesGcmDecryptResult.StructurallyMalformed

        val buffer = ByteBuffer.wrap(payload)
        if (!buffer.hasRemaining()) return AesGcmDecryptResult.StructurallyMalformed
        val ivLength = buffer.get().toInt() and 0xFF
        if (ivLength !in 12..32) return AesGcmDecryptResult.StructurallyMalformed
        if (buffer.remaining() < ivLength + GCM_TAG_BYTES) {
            return AesGcmDecryptResult.StructurallyMalformed
        }
        val iv = ByteArray(ivLength)
        val ciphertext = try {
            buffer.get(iv)
            ByteArray(buffer.remaining()).also { buffer.get(it) }
        } catch (_: BufferUnderflowException) {
            return AesGcmDecryptResult.StructurallyMalformed
        }

        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            AesGcmDecryptResult.Ok(cipher.doFinal(ciphertext))
        } catch (_: AEADBadTagException) {
            AesGcmDecryptResult.CryptographicFailure
        } catch (_: BadPaddingException) {
            AesGcmDecryptResult.CryptographicFailure
        } catch (_: IllegalBlockSizeException) {
            AesGcmDecryptResult.CryptographicFailure
        } catch (_: IllegalArgumentException) {
            AesGcmDecryptResult.StructurallyMalformed
        }
    }
}
