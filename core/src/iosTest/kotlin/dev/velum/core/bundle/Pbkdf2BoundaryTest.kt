package dev.velum.core.bundle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Pbkdf2BoundaryTest {
    // Expected bytes independently generated with Python hashlib.pbkdf2_hmac("sha256", ...).
    @Test
    fun embeddedNulUsesAllPasswordAndSaltBytes() {
        assertKey("aa4399833b716be66298125c3e643697f19ac0a893c1a93ef0665590c0cde816", "pass\u0000word", "sa\u0000lt", 2)
    }

    @Test
    fun unicodeUsesUtf8ByteCount() {
        assertKey("b6358fe75be076eeeb702b8f5c567189cbe416dee1f85a03c37b8cad2ad04b72", "密碼🔐café", "salt", 2)
    }

    @Test
    fun emptyPasswordIsSupported() {
        assertKey("f135c27993baf98773c5cdb40a5706ce6a345cde61b000a67858650cd6a324d7", "", "salt", 1)
    }

    @Test
    fun outputCanSpanMultipleSha256Blocks() {
        assertKey("120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b4dbf3a2f3dad3377", "password", "salt", 1, 40)
    }

    @Test
    fun invalidCountsAreRejectedBeforeNativeConversion() {
        for (iterations in listOf(0, -1, Int.MIN_VALUE)) {
            assertFailsWith<IllegalArgumentException> {
                pbkdf2HmacSha256("password", byteArrayOf(1), iterations, 32)
            }
        }
        for (keyBytes in listOf(0, -1, Int.MIN_VALUE)) {
            assertFailsWith<IllegalArgumentException> {
                pbkdf2HmacSha256("password", byteArrayOf(1), 1, keyBytes)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            pbkdf2HmacSha256("password", byteArrayOf(), 1, 32)
        }
    }

    private fun assertKey(expected: String, password: String, salt: String, iterations: Int, keyBytes: Int = 32) {
        val actual = pbkdf2HmacSha256(password, salt.encodeToByteArray(), iterations, keyBytes)
        assertEquals(expected, actual.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') })
    }
}
