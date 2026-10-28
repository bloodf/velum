package dev.velum.ble.wifi

import dev.velum.ble.crypto.createSessionCipher
import dev.velum.protocol.Protocol20SessionSecret
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WifiFrameCipherNonceTest {
    private val secret = Protocol20SessionSecret(
        key = ByteArray(32) { (it + 5).toByte() },
        nonce = ByteArray(12) { (it + 9).toByte() },
        aad = ByteArray(12) { (it + 13).toByte() },
    )
    private val packet = byteArrayOf(0x01, 0x1A, 0x00, 0x0A, 0x0B)

    @Test
    fun chacha_nonces_differ_across_messages() {
        val cipher = createWifiFrameCipher(
            createSessionCipher(),
            secret,
            WifiPayloadCipher.ChaCha20Poly1305,
        )
        val a = cipher.seal(0, packet)
        val b = cipher.seal(1, packet)
        val nonceA = WifiAeadFrame.extractNonce(a)
        val nonceB = WifiAeadFrame.extractNonce(b)
        assertNotNull(nonceA)
        assertNotNull(nonceB)
        assertFalse(
            nonceA.contentEquals(nonceB),
            "SoftAP ChaCha frames must use distinct AEAD nonces",
        )
        assertFalse(a.contentEquals(b))
        assertTrue(cipher.open(0, a)!!.second.contentEquals(packet))
        assertTrue(cipher.open(1, b)!!.second.contentEquals(packet))
    }

    @Test
    fun aesGcm_nonces_differ_across_messages() {
        val cipher = createWifiFrameCipher(
            createSessionCipher(),
            secret,
            WifiPayloadCipher.AesGcm,
        )
        val a = cipher.seal(0, packet)
        val b = cipher.seal(1, packet)
        val nonceA = WifiAeadFrame.extractNonce(a)
        val nonceB = WifiAeadFrame.extractNonce(b)
        assertNotNull(nonceA)
        assertNotNull(nonceB)
        assertFalse(
            nonceA.contentEquals(nonceB),
            "SoftAP AES-GCM frames must use distinct AEAD nonces",
        )
        assertTrue(cipher.open(0, a)!!.second.contentEquals(packet))
        assertTrue(cipher.open(1, b)!!.second.contentEquals(packet))
    }

    @Test
    fun chacha_tampered_ciphertext_fails_authentication() {
        val cipher = createWifiFrameCipher(
            createSessionCipher(),
            secret,
            WifiPayloadCipher.ChaCha20Poly1305,
        )
        val sealed = cipher.seal(0, packet)
        val tampered = sealed.copyOf()
        // Flip a bit in the ciphertext body (after the nonce prefix).
        val flipAt = WifiAeadFrame.NONCE_LEN + 2
        tampered[flipAt] = (tampered[flipAt].toInt() xor 0x01).toByte()
        assertNull(cipher.open(0, tampered))
        assertTrue(cipher.open(0, sealed)!!.second.contentEquals(packet))
    }

    @Test
    fun aesGcm_tampered_ciphertext_fails_authentication() {
        val cipher = createWifiFrameCipher(
            createSessionCipher(),
            secret,
            WifiPayloadCipher.AesGcm,
        )
        val sealed = cipher.seal(0, packet)
        val tampered = sealed.copyOf()
        val flipAt = WifiAeadFrame.NONCE_LEN + 2
        tampered[flipAt] = (tampered[flipAt].toInt() xor 0x01).toByte()
        assertNull(cipher.open(0, tampered))
        assertTrue(cipher.open(0, sealed)!!.second.contentEquals(packet))
    }

    @Test
    fun chacha_tampered_nonce_fails_authentication() {
        val cipher = createWifiFrameCipher(
            createSessionCipher(),
            secret,
            WifiPayloadCipher.ChaCha20Poly1305,
        )
        val sealed = cipher.seal(0, packet)
        val tampered = sealed.copyOf()
        tampered[0] = (tampered[0].toInt() xor 0x01).toByte()
        assertNull(cipher.open(0, tampered))
    }
}
