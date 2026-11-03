package dev.velum.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

/** Published vectors from RFC 8439. */
class ChaCha20Test {
    private fun hex(text: String): ByteArray =
        text.filterNot { it.isWhitespace() }.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun rfc8439_section_2_4_2_sunscreen() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = hex("000000000000004a00000000")
        val plain = (
            "Ladies and Gentlemen of the class of '99: If I could offer you only one tip " +
                "for the future, sunscreen would be it."
            ).encodeToByteArray()
        val cipher = hex(
            """
            6e2e359a2568f98041ba0728dd0d6981 e97e7aec1d4360c20a27afccfd9fae0b
            f91b65c5524733ab8f593dabcd62b357 1639d624e65152ab8f530c359f0861d8
            07ca0dbf500d6a6156a38e088a22b65e 52bc514d16ccf806818ce91ab7793736
            5af90bbf74a35be6b40b8eedf2785e42 874d
            """,
        )
        assertContentEquals(cipher, ChaCha20.xor(key, nonce, 1L, plain))
        assertContentEquals(plain, ChaCha20.xor(key, nonce, 1L, cipher))
    }

    @Test fun rfc8439_appendix_a_2_test_vector_1_all_zero() {
        val keystream = hex(
            """
            76b8e0ada0f13d90405d6ae55386bd28 bdd219b8a08ded1aa836efcc8b770dc7
            da41597c5157488d7724e03fb8d84a37 6a43b8f41518a11cc387b669b2ee6586
            """,
        )
        assertContentEquals(keystream, ChaCha20.xor(ByteArray(32), ByteArray(12), 0L, ByteArray(64)))
    }

    @Test fun rfc8439_appendix_a_2_test_vector_3_high_counter_key() {
        val key = hex("1c9240a5eb55d38af333888604f6b5f0 473917c1402b80099dca5cbc207075c0")
        val nonce = hex("000000000000000000000002")
        val plain = (
            "'Twas brillig, and the slithy toves\nDid gyre and gimble in the wabe:\n" +
                "All mimsy were the borogoves,\nAnd the mome raths outgrabe."
            ).encodeToByteArray()
        val cipher = hex(
            """
            62e6347f95ed87a45ffae7426f27a1df 5fb69110044c0d73118effa95b01e5cf
            166d3df2d721caf9b21e5fb14c616871 fd84c54f9d65b283196c7fe4f60553eb
            f39c6402c42234e32a356b3e764312a6 1a5532055716ead6962568f87d3f3f77
            04c6a8d1bcd1bf4d50d6154b6da731b1 87b58dfd728afa36757a797ac188d1
            """,
        )
        assertContentEquals(cipher, ChaCha20.xor(key, nonce, 42L, plain))
    }

    @Test fun rejects_wrong_sizes_and_counters() {
        assertFailsWith<IllegalArgumentException> { ChaCha20.xor(ByteArray(31), ByteArray(12), 0L, ByteArray(1)) }
        assertFailsWith<IllegalArgumentException> { ChaCha20.xor(ByteArray(32), ByteArray(16), 0L, ByteArray(1)) }
        assertFailsWith<IllegalArgumentException> { ChaCha20.xor(ByteArray(32), ByteArray(12), -1L, ByteArray(1)) }
    }
}
