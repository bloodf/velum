package dev.velum.ai.local

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

/** #334: a stored key that is not exactly 32 bytes is rejected, never zero-padded into a mostly-zero AES key. */
class ProfileCipherKeyLengthTest {
    @Test fun short_and_long_keys_are_rejected() {
        for (size in listOf(0, 1, 16, 31, 33, 64)) {
            assertFailsWith<VoiceProfileKeyUnavailableException>("size $size") { createProfileCipher(ByteArray(size) { 7 }) }
        }
    }

    @Test fun a_truncated_key_cannot_decrypt_what_the_full_key_wrote() {
        val full = ByteArray(32) { if (it < 16) (it + 1).toByte() else 0 } // zero tail: the old padding of its first 16 bytes
        val sealed = createProfileCipher(full).encrypt(byteArrayOf(1, 2, 3))
        assertFailsWith<VoiceProfileKeyUnavailableException> { createProfileCipher(full.copyOf(16)).decrypt(sealed) }
    }

    @Test fun a_32_byte_key_round_trips() {
        val cipher = createProfileCipher(ByteArray(32) { it.toByte() })
        assertContentEquals(byteArrayOf(9, 8, 7), cipher.decrypt(cipher.encrypt(byteArrayOf(9, 8, 7))))
    }
}
