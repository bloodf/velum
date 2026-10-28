package dev.velum.core.security

import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

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

    fun decrypt(key: SecretKey, encoded: String): ByteArray {
        val payload = Base64.getDecoder().decode(encoded)
        require(payload.isNotEmpty()) { "Empty ciphertext" }
        val buffer = ByteBuffer.wrap(payload)
        val ivLength = buffer.get().toInt() and 0xFF
        require(ivLength in 12..32) { "Unexpected IV length" }
        val iv = ByteArray(ivLength)
        buffer.get(iv)
        val ciphertext = ByteArray(buffer.remaining())
        buffer.get(ciphertext)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }
}
