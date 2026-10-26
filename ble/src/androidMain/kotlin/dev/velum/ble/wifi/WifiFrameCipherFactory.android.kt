package dev.velum.ble.wifi

import dev.velum.protocol.Bytes
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
    WifiPayloadCipher.ChaCha20Poly1305 -> BleSessionWifiFrameCipher(cipher, secret)
    WifiPayloadCipher.AesGcm -> AesGcmWifiFrameCipher(secret)
}

/**
 * AES-GCM Wi-Fi frames with the BLE session key / fixed 12-byte nonce as IV /
 * fixed aad (APK bit 0x08 path). Plaintext = seq u32 LE ‖ packet.
 *
 * Fixed nonce is Plaud parity debt — required for Note Pro SoftAP;
 * Velum SoftAP path uses the same cipher for interoperability.
 * Do not rotate per frame — the device will fail open.
 */
internal class AesGcmWifiFrameCipher(
    private val secret: Protocol20SessionSecret,
) : WifiFrameCipher {
    override val mode: WifiPayloadCipher = WifiPayloadCipher.AesGcm

    override fun seal(sequence: Long, packet: ByteArray): ByteArray {
        val plaintext = Bytes.u32Le(sequence) + packet
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(secret.key, "AES"),
            GCMParameterSpec(128, secret.nonce),
        )
        c.updateAAD(secret.aad)
        return c.doFinal(plaintext)
    }

    override fun open(sequence: Long, ciphertextAndTag: ByteArray): Pair<Long, ByteArray>? {
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(secret.key, "AES"),
                GCMParameterSpec(128, secret.nonce),
            )
            c.updateAAD(secret.aad)
            val plain = c.doFinal(ciphertextAndTag)
            if (plain.size < 4) return null
            val seq = Bytes.readU32Le(plain, 0)
            if (seq != sequence) return null
            seq to plain.copyOfRange(4, plain.size)
        } catch (_: Exception) {
            null
        }
    }
}
