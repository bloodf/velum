package dev.velum.ble.wifi

import dev.velum.protocol.Protocol20SessionSecret
import dev.velum.protocol.SessionCipher
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

actual fun createWifiFrameCipher(
    cipher: SessionCipher,
    secret: Protocol20SessionSecret,
    mode: WifiPayloadCipher,
): WifiFrameCipher = when (mode) {
    WifiPayloadCipher.ChaCha20Poly1305 -> UniqueNonceChaChaWifiFrameCipher(cipher, secret)
    WifiPayloadCipher.AesGcm -> AesGcmWifiFrameCipher(secret)
}

/**
 * AES-GCM SoftAP frames with a unique 96-bit nonce per seal (FeatureRequest bit 0x08).
 * Wire: `nonce[12] ‖ ciphertext‖tag`. Plaintext = `seq_u32_le ‖ packet`.
 * Session key + aad; never reuse a (key, nonce) pair.
 */
internal class AesGcmWifiFrameCipher(
    private val secret: Protocol20SessionSecret,
    private val nextNonce: () -> ByteArray = ::wifiSecureRandomNonce,
) : WifiFrameCipher {
    override val mode: WifiPayloadCipher = WifiPayloadCipher.AesGcm

    override fun seal(sequence: Long, packet: ByteArray): ByteArray {
        val nonce = nextNonce()
        require(nonce.size == WifiAeadFrame.NONCE_LEN) {
            "Wi-Fi AEAD nonce must be ${WifiAeadFrame.NONCE_LEN} bytes"
        }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(secret.key, "AES"),
            GCMParameterSpec(128, nonce),
        )
        c.updateAAD(secret.aad)
        val ct = c.doFinal(WifiAeadFrame.plaintext(sequence, packet))
        return WifiAeadFrame.pack(nonce, ct)
    }

    override fun open(sequence: Long, ciphertextAndTag: ByteArray): Pair<Long, ByteArray>? {
        return try {
            val nonce = WifiAeadFrame.extractNonce(ciphertextAndTag) ?: return null
            val body = WifiAeadFrame.ciphertextAndTag(ciphertextAndTag) ?: return null
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(secret.key, "AES"),
                GCMParameterSpec(128, nonce),
            )
            c.updateAAD(secret.aad)
            val plain = c.doFinal(body)
            WifiAeadFrame.parsePlaintext(plain, sequence)
        } catch (_: Exception) {
            null
        }
    }
}
