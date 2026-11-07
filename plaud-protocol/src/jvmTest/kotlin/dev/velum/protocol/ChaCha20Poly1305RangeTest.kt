package dev.velum.protocol

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ChaCha20Poly1305RangeTest {
    private val key = ByteArray(32) { (it * 7).toByte() }
    private val nonce = ByteArray(12) { (it * 11).toByte() }

    @Test fun jdkDifferentialAcrossPaddingAndOffsets() {
        val sizes = listOf(0, 1, 15, 16, 17, 31, 32, 33, 63, 64, 65, 130)
        for (aadSize in sizes) for (size in sizes) {
            val aad = ByteArray(aadSize) { (it * 13 + aadSize).toByte() }
            val plain = ByteArray(size) { (it * 19 + size).toByte() }
            val cipher = Cipher.getInstance("ChaCha20-Poly1305")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
            cipher.updateAAD(aad)
            val expected = cipher.doFinal(plain)
            assertContentEquals(expected, ChaCha20Poly1305.seal(key, nonce, aad, plain), "aad=$aadSize data=$size")
            for (offset in listOf(0, 1, 17)) {
                val framed = ByteArray(offset) + expected + ByteArray(5)
                assertContentEquals(plain, ChaCha20Poly1305.open(key, nonce, aad, framed, offset, expected.size))
                for (tagByte in 0 until 16) {
                    val tampered = framed.copyOf()
                    val index = offset + size + tagByte
                    tampered[index] = (tampered[index].toInt() xor 1).toByte()
                    assertNull(ChaCha20Poly1305.open(key, nonce, aad, tampered, offset, expected.size))
                }
                if (size > 0) {
                    val tampered = framed.copyOf()
                    tampered[offset] = (tampered[offset].toInt() xor 1).toByte()
                    assertNull(ChaCha20Poly1305.open(key, nonce, aad, tampered, offset, expected.size))
                }
            }
        }
    }

    @Test fun malformedRangesAndCounterLimits() {
        val data = ByteArray(80)
        for ((offset, length) in listOf(-1 to 16, 0 to -1, 81 to 0, 70 to 16, Int.MAX_VALUE to 16, 1 to Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { ChaCha20Poly1305.open(key, nonce, byteArrayOf(), data, offset, length) }
            assertFailsWith<IllegalArgumentException> { ChaCha20.xor(key, nonce, 1, data, offset, length) }
        }
        assertNull(ChaCha20Poly1305.open(key, nonce, byteArrayOf(), data, 65, 15))
        assertContentEquals(
            ChaCha20.xor(key, nonce, 0xffff_ffffL, data.copyOfRange(3, 67)),
            ChaCha20.xor(key, nonce, 0xffff_ffffL, data, 3, 64),
        )
        assertFailsWith<IllegalArgumentException> { ChaCha20.xor(key, nonce, 0xffff_ffffL, data, 3, 65) }
    }
}
