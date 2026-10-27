package dev.velum.ai.local

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

actual fun createProfileCipher(keyMaterial: ByteArray): ProfileCipher =
    AesGcmProfileCipher(keyMaterial)

private class AesGcmProfileCipher(keyMaterial: ByteArray) : ProfileCipher {
    private val key = SecretKeySpec(keyMaterial.copyOf(32), "AES")

    override fun encrypt(plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also { Random.Default.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val out = cipher.doFinal(plain)
        return iv + out
    }

    override fun decrypt(cipher: ByteArray): ByteArray {
        require(cipher.size > 12) { "cipher too short" }
        val iv = cipher.copyOfRange(0, 12)
        val body = cipher.copyOfRange(12, cipher.size)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return c.doFinal(body)
    }
}
