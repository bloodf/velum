package dev.velum.app

import dev.velum.ble.crypto.JcaFileDecryptor
import dev.velum.ble.crypto.JcaSessionCipher
import dev.velum.ble.crypto.rsaKeyPairToPem
import java.security.Security
import javax.crypto.Cipher
import javax.crypto.spec.ChaCha20ParameterSpec
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Production crypto with Conscrypt as provider 1, which Robolectric installs for every test it runs, so this
 * doesn't depend on test order. Conscrypt is Android's default provider, in the version Robolectric brings. Its
 * jar has no macOS arm64 library, so this skips there; on Linux x64, where CI runs, Conscrypt missing fails the
 * test so the coverage can't silently disappear.
 */
@RunWith(RobolectricTestRunner::class)
class ConscryptProviderTest {
    private fun hex(text: String): ByteArray =
        text.filterNot { it.isWhitespace() }.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private val sunscreen = (
        "Ladies and Gentlemen of the class of '99: If I could offer you only one tip " +
            "for the future, sunscreen would be it."
        ).encodeToByteArray()

    private fun withConscryptFirst(block: () -> Unit) {
        val present = Security.getProviders().firstOrNull()?.name == "Conscrypt"
        if (System.getProperty("os.name") == "Linux" && System.getProperty("os.arch") == "amd64") {
            assertTrue(present, "Conscrypt must be the first provider on Linux x64: ${Security.getProviders().map { it.name }}")
        }
        assumeTrue("Conscrypt has no native library for this platform", present)
        block()
    }

    @Test fun plain_chacha20_from_conscrypt_ignores_the_spec_which_is_why_production_avoids_it() = withConscryptFirst {
        val cipher = Cipher.getInstance("ChaCha20")
        assertEquals("Conscrypt", cipher.provider.name)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(ByteArray(32), "ChaCha20"), ChaCha20ParameterSpec(ByteArray(12), 0))
        val keystream = cipher.doFinal(ByteArray(64))
        assertFalse(
            hex("76b8e0ada0f13d90405d6ae55386bd28").contentEquals(keystream.copyOf(16)),
            "Conscrypt now honours the spec: the comment on ChaCha20 needs updating",
        )
    }

    @Test fun file_decryptor_matches_the_rfc_under_conscrypt() = withConscryptFirst {
        val cipher = hex(
            """
            6e2e359a2568f98041ba0728dd0d6981 e97e7aec1d4360c20a27afccfd9fae0b
            f91b65c5524733ab8f593dabcd62b357 1639d624e65152ab8f530c359f0861d8
            07ca0dbf500d6a6156a38e088a22b65e 52bc514d16ccf806818ce91ab7793736
            5af90bbf74a35be6b40b8eedf2785e42 874d
            """,
        )
        val decrypted = JcaFileDecryptor().chacha20Decrypt(ByteArray(32) { it.toByte() }, hex("000000000000004a00000000"), 1L, 0L, cipher)
        assertContentEquals(sunscreen, decrypted)
    }

    @Test fun session_cipher_matches_the_rfc_aead_vector_under_conscrypt() = withConscryptFirst {
        assertEquals("Conscrypt", Cipher.getInstance("ChaCha20-Poly1305").provider.name)
        val key = ByteArray(32) { (0x80 + it).toByte() }
        val nonce = hex("070000004041424344454647")
        val aad = hex("50515253c0c1c2c3c4c5c6c7")
        val sealed = hex(
            """
            d31a8d34648e60db7b86afbc53ef7ec2 a4aded51296e08fea9e2b5a736ee62d6
            3dbea45e8ca9671282fafb69da92728b 1a71de0a9e060b2905d6a5b67ecd3b36
            92ddbd7f2d778b8c9803aee328091b58 fab324e4fad675945585808b4831d7bc
            3ff4def08e4b7a9de576d26586cec64b 6116
            1ae10b594f09e26a7e902ecbd0600691
            """,
        )
        val session = JcaSessionCipher()
        assertContentEquals(sealed, session.chacha20Poly1305Seal(key, nonce, aad, sunscreen))
        assertContentEquals(sunscreen, session.chacha20Poly1305Open(key, nonce, aad, sealed))
    }

    @Test fun session_cipher_fails_closed_under_conscrypt() = withConscryptFirst {
        val key = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(12) { (it + 1).toByte() }
        val aad = ByteArray(12) { (it + 2).toByte() }
        val session = JcaSessionCipher()
        val sealed = session.chacha20Poly1305Seal(key, nonce, aad, ByteArray(40))
        val badTag = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertNull(session.chacha20Poly1305Open(key, nonce, aad, badTag), "bad tag")
        assertNull(session.chacha20Poly1305Open(key, nonce, ByteArray(12), sealed), "wrong aad")
        assertNull(session.chacha20Poly1305Open(ByteArray(32), nonce, aad, sealed), "wrong key")
        assertNull(session.chacha20Poly1305Open(key, nonce, aad, ByteArray(0)), "empty")
        assertNull(session.chacha20Poly1305Open(key, nonce, aad, ByteArray(15)), "shorter than a tag")
    }

    @Test fun aes_gcm_and_rsa_work_under_conscrypt() = withConscryptFirst {
        val gcm = Cipher.getInstance("AES/GCM/NoPadding")
        assertEquals("Conscrypt", gcm.provider.name)
        gcm.init(Cipher.ENCRYPT_MODE, SecretKeySpec(ByteArray(32), "AES"), GCMParameterSpec(128, ByteArray(12)))
        assertContentEquals(hex("cea7403d4d606b6e074ec5d3baf39d18 d0d1c8a799996bf0265b98b5d48ab919"), gcm.doFinal(ByteArray(16)))
        val pems = rsaKeyPairToPem()
        val session = JcaSessionCipher()
        val secret = ByteArray(32) { it.toByte() }
        assertContentEquals(secret, session.rsaDecryptPkcs1(pems.second, session.rsaEncryptPkcs1(pems.first, secret)))
    }
}
