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

/** Cryptographically random material for a newly created profile store. */
expect fun generateProfileKey(): ByteArray

/** Cipher/provider/key access failure; never evidence that an individual file is corrupt. */
class ProfileCipherUnavailableException(cause: Throwable) : IllegalStateException(
    "Voice profile encryption is unavailable. Stored files were preserved.", cause,
)

/** Authentication/wire failure; may mean different key material, not necessarily damaged data. */
class ProfileCiphertextUnreadableException(cause: Throwable? = null) : IllegalArgumentException(
    "Encrypted voice data could not be authenticated.", cause,
)
