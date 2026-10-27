package dev.velum.ai.local

/**
 * AES-GCM (or platform equivalent) for voice-profile embedding blobs.
 * Key material lives in [dev.velum.core.security.SecureStore] — never leave the device.
 */
interface ProfileCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(cipher: ByteArray): ByteArray
}

/** Identity cipher for tests / platforms without crypto. */
object PassthroughProfileCipher : ProfileCipher {
    override fun encrypt(plain: ByteArray): ByteArray = plain.copyOf()
    override fun decrypt(cipher: ByteArray): ByteArray = cipher.copyOf()
}

expect fun createProfileCipher(keyMaterial: ByteArray): ProfileCipher
