package dev.velum.app.crypto

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.velum.ble.crypto.JcaSessionCipher
import dev.velum.ble.crypto.createFileDecryptor
import dev.velum.ble.crypto.rsaKeyPairToPem
import dev.velum.protocol.Bytes
import dev.velum.protocol.ChaCha20
import dev.velum.protocol.PlaudFile
import dev.velum.protocol.PlaudFileCrypto
import java.io.ByteArrayOutputStream
import java.security.Security
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The platform's crypto providers, on a real Android runtime. Read the numbers with `adb logcat -s VelumCrypto`.
 * The test builds its file with [ChaCha20], not the code under test, so a broken production path can't hide
 * by encrypting and decrypting the same wrong way. Synthetic keys and data only.
 */
@RunWith(AndroidJUnit4::class)
class FileDecryptDeviceTest {
    private val key = ByteArray(32) { (it + 1).toByte() }
    private val nonce = ByteArray(12) { (it + 3).toByte() }
    private val pems = rsaKeyPairToPem()

    private fun hex(text: String): ByteArray =
        text.filterNot { it.isWhitespace() }.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** A PLAUD.AI version 1 file. Body counter starts at 1 unless the file is segmented. */
    private fun plaudFile(plain: ByteArray, segmentSize: Int = 0): ByteArray {
        val file = ByteArray(PlaudFile.HEADER_SIZE + plain.size)
        PlaudFile.MAGIC.copyInto(file, 0)
        Bytes.u16Le(1).copyInto(file, PlaudFile.OFF_VERSION)
        Bytes.u16Le(PlaudFile.HEADER_SIZE).copyInto(file, PlaudFile.OFF_HEADER_SIZE)
        Bytes.u16Le(1).copyInto(file, PlaudFile.OFF_FILE_TYPE)
        Bytes.u16Le(1).copyInto(file, PlaudFile.OFF_CHANNEL)
        Bytes.u16Le(1).copyInto(file, PlaudFile.OFF_ENCRYPT_TYPE)
        Bytes.u32Le(1).copyInto(file, PlaudFile.OFF_DURATION)
        Bytes.u32Le(1).copyInto(file, PlaudFile.OFF_CHACHA_COUNTER)
        nonce.copyInto(file, PlaudFile.OFF_CHACHA_NONCE)
        Bytes.u32Le(segmentSize.toLong()).copyInto(file, PlaudFile.OFF_SEGMENT_SIZE)
        JcaSessionCipher().rsaEncryptPkcs1(pems.first, key).copyInto(file, PlaudFile.OFF_KEY_CIPHER)
        val body = if (segmentSize == 0) {
            ChaCha20.xor(key, nonce, 1L, plain)
        } else {
            plain.toList().chunked(segmentSize).flatMap { ChaCha20.xor(key, nonce, 0L, it.toByteArray()).toList() }.toByteArray()
        }
        body.copyInto(file, PlaudFile.HEADER_SIZE)
        return file
    }

    private fun decrypt(file: ByteArray, chunkBytes: Int = PlaudFileCrypto.STREAM_CHUNK_BYTES): ByteArray {
        val out = ByteArrayOutputStream(file.size)
        PlaudFileCrypto.decryptStream(
            file.size.toLong(),
            { position, length -> file.copyOfRange(position.toInt(), position.toInt() + length) },
            pems.second,
            createFileDecryptor(),
            chunkBytes,
        ) { out.write(it) }
        return out.toByteArray()
    }

    @Test fun providers_for_the_log() {
        Log.i(TAG, "api=${Build.VERSION.SDK_INT} abi=${Build.SUPPORTED_ABIS.firstOrNull()} providers=${Security.getProviders().joinToString { it.name }}")
        for (name in listOf("ChaCha20", "ChaCha20-Poly1305", "ChaCha20/Poly1305/NoPadding", "AES/GCM/NoPadding", "RSA/ECB/PKCS1Padding")) {
            Log.i(TAG, "$name -> " + runCatching { Cipher.getInstance(name).provider.name }.getOrElse { "unavailable: ${it::class.simpleName}" })
        }
        val spec = runCatching { Class.forName("javax.crypto.spec.ChaCha20ParameterSpec"); "present" }.getOrElse { "absent" }
        Log.i(TAG, "ChaCha20ParameterSpec $spec")
    }

    @Test fun file_through_the_production_path_equals_the_plaintext() {
        val plain = Random(1).nextBytes(3 * PlaudFileCrypto.STREAM_CHUNK_BYTES + 1_000)
        assertArrayEquals(plain, decrypt(plaudFile(plain)))
    }

    @Test fun rfc8439_vectors_through_the_production_decryptor() {
        val decryptor = createFileDecryptor()
        val sunscreen = (
            "Ladies and Gentlemen of the class of '99: If I could offer you only one tip " +
                "for the future, sunscreen would be it."
            ).encodeToByteArray()
        val sunscreenCipher = hex(
            """
            6e2e359a2568f98041ba0728dd0d6981 e97e7aec1d4360c20a27afccfd9fae0b
            f91b65c5524733ab8f593dabcd62b357 1639d624e65152ab8f530c359f0861d8
            07ca0dbf500d6a6156a38e088a22b65e 52bc514d16ccf806818ce91ab7793736
            5af90bbf74a35be6b40b8eedf2785e42 874d
            """,
        )
        assertArrayEquals(
            sunscreen,
            decryptor.chacha20Decrypt(ByteArray(32) { it.toByte() }, hex("000000000000004a00000000"), 1L, 0L, sunscreenCipher),
        )
        val zeroBlock = hex(
            """
            76b8e0ada0f13d90405d6ae55386bd28 bdd219b8a08ded1aa836efcc8b770dc7
            da41597c5157488d7724e03fb8d84a37 6a43b8f41518a11cc387b669b2ee6586
            """,
        )
        assertArrayEquals(ByteArray(64), decryptor.chacha20Decrypt(ByteArray(32), ByteArray(12), 0L, 0L, zeroBlock))
    }

    @Test fun counter_stays_right_across_chunks_and_segments() {
        val plain = Random(2).nextBytes(10_000)
        assertArrayEquals("chunks of 3 blocks", plain, decrypt(plaudFile(plain), chunkBytes = 192))
        assertArrayEquals("chunks of 1 block", plain, decrypt(plaudFile(plain), chunkBytes = 64))
        assertArrayEquals("segments of 512, whole", plain, decrypt(plaudFile(plain, segmentSize = 512), chunkBytes = 1_024))
        assertArrayEquals("segments of 4096, split", plain, decrypt(plaudFile(plain, segmentSize = 4_096), chunkBytes = 1_024))
    }

    /** RFC 8439 section 2.8.2, through the BLE session cipher. */
    @Test fun session_cipher_matches_the_rfc8439_aead_vector() {
        val aeadKey = ByteArray(32) { (0x80 + it).toByte() }
        val aeadNonce = hex("070000004041424344454647")
        val aad = hex("50515253c0c1c2c3c4c5c6c7")
        val plain = (
            "Ladies and Gentlemen of the class of '99: If I could offer you only one tip " +
                "for the future, sunscreen would be it."
            ).encodeToByteArray()
        val sealed = hex(
            """
            d31a8d34648e60db7b86afbc53ef7ec2 a4aded51296e08fea9e2b5a736ee62d6
            3dbea45e8ca9671282fafb69da92728b 1a71de0a9e060b2905d6a5b67ecd3b36
            92ddbd7f2d778b8c9803aee328091b58 fab324e4fad675945585808b4831d7bc
            3ff4def08e4b7a9de576d26586cec64b 6116
            1ae10b594f09e26a7e902ecbd0600691
            """,
        )
        val cipher = JcaSessionCipher()
        assertArrayEquals(sealed, cipher.chacha20Poly1305Seal(aeadKey, aeadNonce, aad, plain))
        assertArrayEquals(plain, cipher.chacha20Poly1305Open(aeadKey, aeadNonce, aad, sealed))
    }

    /** NIST GCM test case 14, in the form the Wi-Fi frame and voice profile ciphers use. */
    @Test fun aes_gcm_honours_the_given_iv() {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(ByteArray(32), "AES"), GCMParameterSpec(128, ByteArray(12)))
        assertArrayEquals(hex("cea7403d4d606b6e074ec5d3baf39d18 d0d1c8a799996bf0265b98b5d48ab919"), cipher.doFinal(ByteArray(16)))
    }

    @Test fun throughput_for_the_log() {
        val plain = Random(3).nextBytes(16 * 1024 * 1024)
        val file = plaudFile(plain)
        val started = System.nanoTime()
        var written = 0L
        PlaudFileCrypto.decryptStream(
            file.size.toLong(),
            { position, length -> file.copyOfRange(position.toInt(), position.toInt() + length) },
            pems.second,
            createFileDecryptor(),
        ) { written += it.size }
        val seconds = (System.nanoTime() - started) / 1e9
        Log.i(TAG, "decrypt ${written / (1024 * 1024)} MiB in %.2f s, %.1f MiB/s".format(seconds, written / (1024.0 * 1024.0) / seconds))
    }

    private companion object { const val TAG = "VelumCrypto" }
}
