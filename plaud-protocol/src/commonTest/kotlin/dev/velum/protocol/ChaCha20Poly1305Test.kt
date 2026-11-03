package dev.velum.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChaCha20Poly1305Test {
    private fun hex(s: String): ByteArray {
        val h = s.filter { !it.isWhitespace() }
        return ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private val key = ByteArray(32) { (0x80 + it).toByte() }
    private val nonce = hex("070000004041424344454647")
    private val aad = hex("50515253c0c1c2c3c4c5c6c7")
    private val plain = (
        "Ladies and Gentlemen of the class of '99: If I could offer you only one tip " +
            "for the future, sunscreen would be it."
        ).encodeToByteArray()

    /** RFC 8439 section 2.8.2. */
    private val sealed = hex(
        """
        d31a8d34648e60db7b86afbc53ef7ec2 a4aded51296e08fea9e2b5a736ee62d6
        3dbea45e8ca9671282fafb69da92728b 1a71de0a9e060b2905d6a5b67ecd3b36
        92ddbd7f2d778b8c9803aee328091b58 fab324e4fad675945585808b4831d7bc
        3ff4def08e4b7a9de576d26586cec64b 6116
        1ae10b594f09e26a7e902ecbd0600691
        """,
    )

    @Test fun seals_the_rfc8439_vector() {
        assertContentEquals(sealed, ChaCha20Poly1305.seal(key, nonce, aad, plain))
    }

    @Test fun opens_the_rfc8439_vector() {
        assertContentEquals(plain, ChaCha20Poly1305.open(key, nonce, aad, sealed))
    }

    @Test fun rejects_a_flipped_bit_in_body_tag_or_aad_and_short_input() {
        val body = sealed.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val tag = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertNull(ChaCha20Poly1305.open(key, nonce, aad, body))
        assertNull(ChaCha20Poly1305.open(key, nonce, aad, tag))
        assertNull(ChaCha20Poly1305.open(key, nonce, aad.copyOf().also { it[0] = 0 }, sealed))
        assertNull(ChaCha20Poly1305.open(key, nonce, aad, ByteArray(15)))
    }

    @Test fun roundTripsEveryLengthAcrossBlockEdges() {
        for (n in 0..130) {
            val data = ByteArray(n) { (it * 7 + n).toByte() }
            val ad = ByteArray(n % 33) { it.toByte() }
            val boxed = ChaCha20Poly1305.seal(key, nonce, ad, data)
            assertEquals(n + 16, boxed.size)
            assertContentEquals(data, ChaCha20Poly1305.open(key, nonce, ad, boxed), "length $n")
        }
    }
}
