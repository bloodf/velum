package dev.velum.ble.crypto

import dev.velum.protocol.Bytes
import dev.velum.protocol.PlaudFile
import dev.velum.protocol.PlaudFileCrypto
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.ChaCha20ParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** The file decryptor must not depend on which JCA provider is first: Conscrypt ignores the nonce (#94). */
class FileDecryptorChaCha20Test {
    private val key = Random(1).nextBytes(32)
    private val nonce = Random(2).nextBytes(12)

    @Test fun matches_sunjce_across_block_edges_and_counters() {
        val decryptor = JcaFileDecryptor()
        for (size in listOf(1, 63, 64, 65, 127, 128, 129, 1_000, 262_144)) {
            for (counter in listOf(0, 1, 7, 1_000)) {
                val data = Random(size).nextBytes(size)
                val cipher = Cipher.getInstance("ChaCha20", "SunJCE")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "ChaCha20"), ChaCha20ParameterSpec(nonce, counter))
                assertContentEquals(cipher.doFinal(data), decryptor.chacha20Decrypt(key, nonce, counter.toLong(), 0L, data), "size=$size counter=$counter")
            }
        }
    }

    @Test fun the_last_counter_block_matches_sunjce() {
        val data = Random(3).nextBytes(64)
        val cipher = Cipher.getInstance("ChaCha20", "SunJCE")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "ChaCha20"), ChaCha20ParameterSpec(nonce, -1))
        assertContentEquals(cipher.doFinal(data), JcaFileDecryptor().chacha20Decrypt(key, nonce, 0xFFFF_FFFFL, 0L, data))
    }

    @Test fun a_file_whose_counter_would_wrap_fails_in_the_stream_path_too() {
        val pems = rsaKeyPairToPem()
        val decryptor = JcaFileDecryptor()
        val file = decryptor.encryptFile(pems.first, ByteArray(1_000))
        Bytes.u32Le(0xFFFF_FFFFL).copyInto(file, PlaudFile.OFF_CHACHA_COUNTER)
        val read = { position: Long, length: Int -> file.copyOfRange(position.toInt(), position.toInt() + length) }
        // Wraps inside the first chunk, and with small chunks a later chunk starts past the limit.
        for (chunkBytes in listOf(PlaudFileCrypto.STREAM_CHUNK_BYTES, 64)) {
            assertFailsWith<IllegalArgumentException> {
                PlaudFileCrypto.decryptStream(file.size.toLong(), read, pems.second, decryptor, chunkBytes) {}
            }
        }
    }

    @Test fun jca_decryptors_do_not_ask_a_provider_for_plain_chacha20() {
        for (path in listOf("src/jvmMain/kotlin/dev/velum/ble/crypto/JcaCrypto.kt", "src/androidMain/kotlin/dev/velum/ble/crypto/JcaCrypto.kt")) {
            assertFalse("Cipher.getInstance(\"ChaCha20\")" in File(path).readText(), path)
        }
    }
}
