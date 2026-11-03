package dev.velum.protocol

import java.math.BigInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals

/** Poly1305 against the definition, computed with BigInteger: random keys (so r gets clamped) and lengths. */
class Poly1305ReferenceTest {
    private val p = BigInteger.ONE.shiftLeft(130) - BigInteger.valueOf(5)
    private val clampMask = BigInteger("0ffffffc0ffffffc0ffffffc0fffffff", 16)

    private fun le(bytes: ByteArray, from: Int, to: Int): BigInteger =
        BigInteger(1, bytes.copyOfRange(from, to).reversedArray())

    private fun reference(key: ByteArray, message: ByteArray): ByteArray {
        val r = le(key, 0, 16).and(clampMask)
        var h = BigInteger.ZERO
        for (i in message.indices step 16) {
            val block = message.copyOfRange(i, i + 16) + byteArrayOf(1)
            h = (h + le(block, 0, 17)).multiply(r).mod(p)
        }
        val tag = h.add(le(key, 16, 32)).mod(BigInteger.ONE.shiftLeft(128))
        val be = tag.toByteArray().let { if (it.size > 16) it.copyOfRange(it.size - 16, it.size) else it }
        return ByteArray(16 - be.size) + be
    }

    private fun toLe16(be: ByteArray) = be.reversedArray()

    @Test fun randomKeysAndLengthsMatchTheBigIntegerReference() {
        val random = Random(2026)
        repeat(600) { i ->
            val key = random.nextBytes(32)
            // High bits set on r so the clamp matters; the message has 0 to 40 blocks.
            if (i % 3 == 0) for (k in 0 until 16) key[k] = (key[k].toInt() or 0xFF).toByte()
            val message = random.nextBytes(16 * random.nextInt(0, 41))
            assertContentEquals(toLe16(reference(key, message)), ChaCha20Poly1305.poly1305(key, message), "case $i")
        }
    }

    @Test fun allOnesKeyAndMessageMatchTheReference() {
        val key = ByteArray(32) { 0xFF.toByte() }
        for (blocks in 0..9) {
            val message = ByteArray(16 * blocks) { 0xFF.toByte() }
            assertContentEquals(toLe16(reference(key, message)), ChaCha20Poly1305.poly1305(key, message), "blocks $blocks")
        }
    }
}
