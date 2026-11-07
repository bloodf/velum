package dev.velum.ai.local

import java.security.SecureRandom
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException

actual fun createProfileCipher(keyMaterial: ByteArray): ProfileCipher = AesGcmProfileCipher(keyMaterial)

private class AesGcmProfileCipher(keyMaterial: ByteArray) : ProfileCipher {
    // A short key must never be zero-padded into a weak AES key: treat it like any unusable stored key (#334).
    private val key = SecretKeySpec(
        keyMaterial.takeIf { it.size == 32 } ?: throw VoiceProfileKeyUnavailableException(),
        "AES",
    )

    override fun encrypt(plain: ByteArray): ByteArray = cipherAccess {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        iv + cipher.doFinal(plain)
    }

    override fun decrypt(cipher: ByteArray): ByteArray {
        if (cipher.size <= 12) throw ProfileCiphertextUnreadableException()
        return cipherAccess {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, cipher.copyOfRange(0, 12)))
            try {
                c.doFinal(cipher.copyOfRange(12, cipher.size))
            } catch (failure: BadPaddingException) {
                throw ProfileCiphertextUnreadableException(failure)
            } catch (failure: IllegalBlockSizeException) {
                throw ProfileCiphertextUnreadableException(failure)
            }
        }
    }
}

private inline fun <T> cipherAccess(block: () -> T): T = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (unreadable: ProfileCiphertextUnreadableException) {
    throw unreadable
} catch (failure: Exception) {
    throw ProfileCipherUnavailableException(failure)
}

actual fun generateProfileKey(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }
