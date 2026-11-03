package dev.velum.ble.crypto

import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.ChaCha20ParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
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

    @Test fun jca_decryptors_do_not_ask_a_provider_for_plain_chacha20() {
        for (path in listOf("src/jvmMain/kotlin/dev/velum/ble/crypto/JcaCrypto.kt", "src/androidMain/kotlin/dev/velum/ble/crypto/JcaCrypto.kt")) {
            assertFalse("Cipher.getInstance(\"ChaCha20\")" in File(path).readText(), path)
        }
    }
}
