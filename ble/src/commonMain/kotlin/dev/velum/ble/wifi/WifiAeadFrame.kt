package dev.velum.ble.wifi

import dev.velum.protocol.Bytes
import dev.velum.protocol.Protocol20SessionSecret
import dev.velum.protocol.SessionCipher

/**
 * SoftAP / Wi-Fi AEAD frame layout (Velum).
 *
 * Wire: `nonce[12] ‖ ciphertext‖tag`
 * Plaintext under the AEAD: `seq_u32_le ‖ packet`
 * Key / AAD: BLE session secret (unchanged). Nonce: unique 96-bit per message
 * from a CSPRNG — never reuse a (key, nonce) pair on this path.
 *
 * BLE link packets still use the Plaud fixed session nonce via [SessionCipher];
 * only SoftAP WebSocket frames use this layout.
 */
object WifiAeadFrame {
    const val NONCE_LEN: Int = 12

    fun extractNonce(frame: ByteArray): ByteArray? {
        if (frame.size < NONCE_LEN) return null
        return frame.copyOfRange(0, NONCE_LEN)
    }

    fun ciphertextAndTag(frame: ByteArray): ByteArray? {
        if (frame.size <= NONCE_LEN) return null
        return frame.copyOfRange(NONCE_LEN, frame.size)
    }

    fun pack(nonce: ByteArray, ciphertextAndTag: ByteArray): ByteArray {
        require(nonce.size == NONCE_LEN) { "Wi-Fi AEAD nonce must be $NONCE_LEN bytes" }
        return nonce + ciphertextAndTag
    }

    fun plaintext(sequence: Long, packet: ByteArray): ByteArray =
        Bytes.u32Le(sequence) + packet

    fun parsePlaintext(plain: ByteArray, expectedSequence: Long): Pair<Long, ByteArray>? {
        if (plain.size < 4) return null
        val seq = Bytes.readU32Le(plain, 0)
        if (seq != expectedSequence) return null
        return seq to plain.copyOfRange(4, plain.size)
    }
}

/**
 * ChaCha20-Poly1305 SoftAP frames with a unique 96-bit nonce per seal.
 * Frame = nonce ‖ ciphertext‖tag.
 */
class UniqueNonceChaChaWifiFrameCipher(
    private val cipher: SessionCipher,
    private val secret: Protocol20SessionSecret,
    private val nextNonce: () -> ByteArray = ::wifiSecureRandomNonce,
) : WifiFrameCipher {
    override val mode: WifiPayloadCipher = WifiPayloadCipher.ChaCha20Poly1305

    override fun seal(sequence: Long, packet: ByteArray): ByteArray {
        val nonce = nextNonce()
        require(nonce.size == WifiAeadFrame.NONCE_LEN) {
            "Wi-Fi AEAD nonce must be ${WifiAeadFrame.NONCE_LEN} bytes"
        }
        val ct = cipher.chacha20Poly1305Seal(
            secret.key,
            nonce,
            secret.aad,
            WifiAeadFrame.plaintext(sequence, packet),
        )
        return WifiAeadFrame.pack(nonce, ct)
    }

    override fun open(sequence: Long, ciphertextAndTag: ByteArray): Pair<Long, ByteArray>? {
        val nonce = WifiAeadFrame.extractNonce(ciphertextAndTag) ?: return null
        val body = WifiAeadFrame.ciphertextAndTag(ciphertextAndTag) ?: return null
        val plain = cipher.chacha20Poly1305Open(secret.key, nonce, secret.aad, body) ?: return null
        return WifiAeadFrame.parsePlaintext(plain, sequence)
    }
}
