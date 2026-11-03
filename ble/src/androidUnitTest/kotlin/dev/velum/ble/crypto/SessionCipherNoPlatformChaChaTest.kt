package dev.velum.ble.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.Provider
import java.security.Security
import javax.crypto.Cipher
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * #226: API 26 and 27 have no ChaCha20/Poly1305 cipher (documented from API 28). This removes every provider
 * that offers it, which is what those releases look like to `Cipher.getInstance`, and checks that the session
 * cipher still produces and opens the RFC 8439 AEAD vector.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SessionCipherNoPlatformChaChaTest {
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
    private val sealed = hex(
        """
        d31a8d34648e60db7b86afbc53ef7ec2 a4aded51296e08fea9e2b5a736ee62d6
        3dbea45e8ca9671282fafb69da92728b 1a71de0a9e060b2905d6a5b67ecd3b36
        92ddbd7f2d778b8c9803aee328091b58 fab324e4fad675945585808b4831d7bc
        3ff4def08e4b7a9de576d26586cec64b 6116
        1ae10b594f09e26a7e902ecbd0600691
        """,
    )

    private fun <T> withoutPlatformChaCha(block: () -> T): T {
        val removed = mutableListOf<Pair<Int, Provider>>()
        Security.getProviders().forEachIndexed { i, p ->
            if (PLATFORM_CHACHA_TRANSFORMATIONS.any { name -> p.getService("Cipher", name) != null }) removed += (i + 1) to p
        }
        removed.forEach { Security.removeProvider(it.second.name) }
        try {
            for (name in PLATFORM_CHACHA_TRANSFORMATIONS) {
                assertFailsWith<java.security.NoSuchAlgorithmException>("platform ChaCha '$name' must be gone") {
                    Cipher.getInstance(name)
                }
            }
            return block()
        } finally {
            removed.sortedBy { it.first }.forEach { (pos, p) -> Security.insertProviderAt(p, pos) }
        }
    }

    /**
     * What an API 28 image does: only the documented full name resolves, the bare alias throws. The JDK stands in
     * for the platform cipher behind the full name; [asked] records every name the code requests, in order.
     */
    private fun api28Resolver(asked: MutableList<String>): (String) -> Cipher = { name ->
        asked += name
        if (name == "ChaCha20/Poly1305/NoPadding") Cipher.getInstance("ChaCha20-Poly1305")
        else throw java.security.NoSuchAlgorithmException(name)
    }

    @Test
    fun theFullTransformationNameIsAskedFirstAndSelectsThePlatformCipher() {
        val asked = mutableListOf<String>()
        val cipher = JcaSessionCipher(api28Resolver(asked))
        assertTrue(cipher.usesPlatformChaCha, "the full name must select the platform path")
        assertEquals("ChaCha20/Poly1305/NoPadding", asked.first())
        assertContentEquals(sealed, cipher.chacha20Poly1305Seal(key, nonce, aad, plain))
        assertContentEquals(plain, cipher.chacha20Poly1305Open(key, nonce, aad, sealed))
        assertNull(cipher.chacha20Poly1305Open(key, nonce, aad, sealed.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }))
        assertTrue("ChaCha20-Poly1305" !in asked, "the alias must not be asked once the full name works: $asked")
    }

    @Test
    fun theAliasIsTheFallbackWhenTheFullNameIsMissing() {
        val asked = mutableListOf<String>()
        val cipher = JcaSessionCipher { name ->
            asked += name
            if (name == "ChaCha20-Poly1305") Cipher.getInstance(name) else throw java.security.NoSuchAlgorithmException(name)
        }
        assertTrue(cipher.usesPlatformChaCha)
        assertEquals(listOf("ChaCha20/Poly1305/NoPadding", "ChaCha20-Poly1305"), asked.take(2))
    }

    @Test
    fun theKnownAnswerProbeAcceptsAWorkingPlatformCipherAndRejectsAMissingOne() {
        assertTrue(JcaSessionCipher().usesPlatformChaCha, "a working platform cipher must pass the probe")
        withoutPlatformChaCha { assertTrue(!JcaSessionCipher().usesPlatformChaCha) }
    }

    @Test
    fun sealsAndOpensTheRfcVectorWithoutAPlatformCipher() = withoutPlatformChaCha {
        val cipher = JcaSessionCipher()
        assertContentEquals(sealed, cipher.chacha20Poly1305Seal(key, nonce, aad, plain))
        assertContentEquals(plain, cipher.chacha20Poly1305Open(key, nonce, aad, sealed))
    }

    @Test
    fun stillFailsClosedOnABadTagWithoutAPlatformCipher() = withoutPlatformChaCha {
        val bad = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertNull(JcaSessionCipher().chacha20Poly1305Open(key, nonce, aad, bad))
    }

    @Test
    fun packetsSealedWithAPlatformCipherOpenWithoutOne() {
        val secret = dev.velum.protocol.Protocol20SessionSecret(key, nonce, aad)
        val withPlatform = JcaSessionCipher().sealPacket(secret, 5, byteArrayOf(1, 2, 3))
        withoutPlatformChaCha {
            val opened = JcaSessionCipher().openPacket(secret, 5, withPlatform)
            assertTrue(opened != null && opened.second.contentEquals(byteArrayOf(1, 2, 3)))
        }
    }
}
