package dev.velum.ble.crypto

import dev.velum.ble.wifi.WifiPayloadCipher
import dev.velum.ble.wifi.createWifiFrameCipher
import dev.velum.protocol.Protocol20SessionSecret
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

class AppleWifiCipherTest {
    @Test fun aes256_gcm_known_answer_and_sequence_authentication() {
        val secret = Protocol20SessionSecret(ByteArray(32), ByteArray(12), ByteArray(12))
        val cipher = createWifiFrameCipher(createSessionCipher(), secret, WifiPayloadCipher.AesGcm)
        // Independently generated AES-256-GCM: zero key/nonce, twelve zero AAD bytes, four zero plaintext bytes.
        val expected = "cea7403de081858eb642b8d750c474efccb882b1".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertContentEquals(expected, cipher.seal(0, ByteArray(0)))
        assertContentEquals(ByteArray(0), cipher.open(0, expected)?.second)
        assertNull(cipher.open(1, expected))
        assertNull(cipher.open(0, expected.copyOf().also { it[0] = 0 }))
        assertNull(cipher.open(0, ByteArray(15)))
    }

    @Test fun both_wifi_modes_check_sequence_and_tag() {
        val secret = Protocol20SessionSecret(ByteArray(32), ByteArray(12), ByteArray(12))
        for (mode in listOf(WifiPayloadCipher.AesGcm, WifiPayloadCipher.ChaCha20Poly1305)) {
            val cipher = createWifiFrameCipher(createSessionCipher(), secret, mode)
            val sealed = cipher.seal(42, byteArrayOf(7))
            assertContentEquals(byteArrayOf(7), cipher.open(42, sealed)?.second)
            assertNull(cipher.open(43, sealed))
            assertNull(cipher.open(42, sealed.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }))
        }
    }
}
