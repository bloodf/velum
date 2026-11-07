package dev.velum.app.bundle

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.velum.core.bundle.SealedBundle
import dev.velum.core.bundle.pbkdf2HmacSha256
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #71: Android's own PBKDF2WithHmacSHA256 (not the JDK's) must derive the same bytes as every other platform, including
 * for a non-ASCII passphrase, or a bundle sealed on one device would not open on another. Run on API 26 and a current API.
 *
 * ./gradlew :composeApp:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.velum.app.bundle.Pbkdf2DeviceTest
 * (or `adb -s <serial> shell am instrument -w -e class dev.velum.app.bundle.Pbkdf2DeviceTest <test package>/<runner>`)
 */
@RunWith(AndroidJUnit4::class)
class Pbkdf2DeviceTest {
    private fun hex(b: ByteArray) = b.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    @Test fun ascii_vectors() {
        assertEquals("120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b", hex(pbkdf2HmacSha256("password", "salt".encodeToByteArray(), 1, 32)))
        assertEquals("c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a", hex(pbkdf2HmacSha256("password", "salt".encodeToByteArray(), 4096, 32)))
    }

    /** "café" in NFC, salt "salt", 1000 iterations: same value on the JVM (JDK) and the reference. */
    @Test fun non_ascii_vector_is_utf8() {
        assertEquals("5396cb973d99ae225f9113732d095024ed7f033f3b1b47bcd3b58b3f7ed5f73d", hex(pbkdf2HmacSha256("café", "salt".encodeToByteArray(), 1000, 32)))
    }

    @Test fun seal_and_open_round_trip_with_a_composed_and_a_decomposed_passphrase() {
        val plain = "synthetic".encodeToByteArray()
        val sealed = SealedBundle.seal("café", plain, SealedBundle.MIN_ITERATIONS)
        assertArrayEquals(plain, SealedBundle.open("café", sealed))
    }
}
