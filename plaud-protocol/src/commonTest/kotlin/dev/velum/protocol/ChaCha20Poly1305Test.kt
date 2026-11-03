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

    // RFC 8439 A.3 edge vectors #5 to #11 (key = r then s). They exercise the final carry and final reduction,
    // which the section 2.8.2 vector alone does not.
    private fun mac(r: String, s: String, msg: String) = ChaCha20Poly1305.poly1305(hex(r + s), hex(msg))
    private val zero16 = "00000000000000000000000000000000"
    private val ones16 = "ffffffffffffffffffffffffffffffff"

    @Test fun rfcA3_zeroKeyAndMessage_is_zero() {
        assertContentEquals(hex(zero16), ChaCha20Poly1305.poly1305(ByteArray(32), ByteArray(64)))
    }

    @Test fun rfcA3_5_partialReduction() =
        assertContentEquals(hex("03000000000000000000000000000000"), mac("02000000000000000000000000000000", zero16, ones16))

    @Test fun rfcA3_6_sAdditionOverflowsMod2to128() =
        assertContentEquals(
            hex("03000000000000000000000000000000"),
            mac("02000000000000000000000000000000", ones16, "02000000000000000000000000000000"),
        )

    @Test fun rfcA3_7_carryFromLowerLimb() = assertContentEquals(
        hex("05000000000000000000000000000000"),
        mac("01000000000000000000000000000000", zero16, ones16 + "f0ffffffffffffffffffffffffffffff" + "11000000000000000000000000000000"),
    )

    @Test fun rfcA3_8_resultExactly_2to130_minus_5() = assertContentEquals(
        hex(zero16),
        mac("01000000000000000000000000000000", zero16, ones16 + "fbfefefefefefefefefefefefefefefe" + "01010101010101010101010101010101"),
    )

    @Test fun rfcA3_9_resultExactly_2to130_minus_6() = assertContentEquals(
        hex("faffffffffffffffffffffffffffffff"),
        mac("02000000000000000000000000000000", zero16, "fdffffffffffffffffffffffffffffff"),
    )

    @Test fun rfcA3_10_131bitIntermediate() = assertContentEquals(
        hex("14000000000000005500000000000000"),
        mac(
            "01000000000000000400000000000000", zero16,
            "e33594d7505e43b90000000000000000" + "3394d7505e4379cd0100000000000000" + zero16 + "01000000000000000000000000000000",
        ),
    )

    @Test fun rfcA3_11_131bitFinal() = assertContentEquals(
        hex("13000000000000000000000000000000"),
        mac(
            "01000000000000000400000000000000", zero16,
            "e33594d7505e43b90000000000000000" + "3394d7505e4379cd0100000000000000" + zero16,
        ),
    )

    @Test fun shortFrames_length0_1_and_a_wrong16() {
        val empty = ChaCha20Poly1305.seal(key, nonce, aad, ByteArray(0))
        assertEquals(16, empty.size)
        assertContentEquals(ByteArray(0), ChaCha20Poly1305.open(key, nonce, aad, empty))
        assertNull(ChaCha20Poly1305.open(key, nonce, aad, ByteArray(0)))
        assertNull(ChaCha20Poly1305.open(key, nonce, aad, ByteArray(1)))
        assertNull(ChaCha20Poly1305.open(key, nonce, aad, ByteArray(16)), "a zero tag is not valid")
    }
}
