package dev.velum.ble.crypto

import dev.velum.protocol.ChaCha20Poly1305
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals

/** The Kotlin AEAD against the JDK's ChaCha20-Poly1305, both directions, across lengths and AAD sizes. */
class ChaCha20Poly1305PlatformDifferentialTest {
    private fun platform(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray, input: ByteArray): ByteArray {
        val c = Cipher.getInstance("ChaCha20-Poly1305")
        c.init(mode, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        c.updateAAD(aad)
        return c.doFinal(input)
    }

    @Test fun kotlinMatchesAndInteroperatesWithThePlatformCipher() {
        val random = Random(226)
        val lengths = (0..200).toList() + listOf(255, 256, 257, 1000, 4096, 4097)
        for (n in lengths) {
            val key = random.nextBytes(32)
            val nonce = random.nextBytes(12)
            val aad = random.nextBytes(random.nextInt(0, 40))
            val plain = random.nextBytes(n)
            val fromPlatform = platform(Cipher.ENCRYPT_MODE, key, nonce, aad, plain)
            val fromKotlin = ChaCha20Poly1305.seal(key, nonce, aad, plain)
            assertContentEquals(fromPlatform, fromKotlin, "seal, length $n")
            // platform to Kotlin, and Kotlin to platform
            assertContentEquals(plain, ChaCha20Poly1305.open(key, nonce, aad, fromPlatform), "Kotlin opens platform, length $n")
            assertContentEquals(plain, platform(Cipher.DECRYPT_MODE, key, nonce, aad, fromKotlin), "platform opens Kotlin, length $n")
        }
    }
}
