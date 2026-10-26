package dev.velum.ble.crypto

import dev.velum.protocol.Bytes
import dev.velum.protocol.FileDecryptor
import dev.velum.protocol.Protocol20SessionSecret
import dev.velum.protocol.SessionCipher
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.ChaCha20ParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

actual fun createSessionCipher(): SessionCipher = JcaSessionCipher()

actual fun createFileDecryptor(): FileDecryptor = JcaFileDecryptor()

/**
 * JCA-backed protocol-20 crypto (ChaCha20-Poly1305 + RSA PKCS#1 v1.5).
 */
class JcaSessionCipher : SessionCipher {
    override fun rsaDecryptPkcs1(privateKeyPem: String, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.DECRYPT_MODE, parsePrivateKey(privateKeyPem))
        return cipher.doFinal(ciphertext)
    }

    override fun rsaEncryptPkcs1(publicKeyPem: String, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, parsePublicKey(publicKeyPem))
        return cipher.doFinal(plaintext)
    }

    override fun chacha20Poly1305Open(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        ciphertextAndTag: ByteArray,
    ): ByteArray? {
        return try {
            val cipher = Cipher.getInstance("ChaCha20-Poly1305")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "ChaCha20"),
                IvParameterSpec(nonce),
            )
            cipher.updateAAD(aad)
            cipher.doFinal(ciphertextAndTag)
        } catch (_: Exception) {
            null
        }
    }

    override fun chacha20Poly1305Seal(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        plaintext: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance("ChaCha20-Poly1305")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "ChaCha20"),
            IvParameterSpec(nonce),
        )
        cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    /**
     * plaintext = seq_u32_le || packet;
     * nonce / aad fixed for the whole session (APK 3.30.0 — do not fold seq into nonce).
     * Fixed nonce is Plaud protocol parity debt — required for Note Pro SoftAP / BLE interoperability.
     */
    override fun sealPacket(
        secret: Protocol20SessionSecret,
        sequence: Long,
        packet: ByteArray,
    ): ByteArray {
        val plaintext = Bytes.u32Le(sequence) + packet
        return chacha20Poly1305Seal(secret.key, secret.nonce, secret.aad, plaintext)
    }

    override fun openPacket(
        secret: Protocol20SessionSecret,
        sequence: Long,
        ciphertextAndTag: ByteArray,
    ): Pair<Long, ByteArray>? {
        val plain = chacha20Poly1305Open(secret.key, secret.nonce, secret.aad, ciphertextAndTag)
            ?: return null
        if (plain.size < 4) return null
        val seq = Bytes.readU32Le(plain, 0)
        if (seq != sequence) return null
        return seq to plain.copyOfRange(4, plain.size)
    }
}

/**
 * JCA plain ChaCha20 (RFC 8439, no Poly1305) + RSA unwrap for PLAUD.AI files.
 */
class JcaFileDecryptor : FileDecryptor {
    private val session = JcaSessionCipher()

    override fun unwrapContentKey(privateKeyPem: String, keyCipher: ByteArray): ByteArray =
        session.rsaDecryptPkcs1(privateKeyPem, keyCipher)

    override fun chacha20Decrypt(
        key: ByteArray,
        nonce: ByteArray,
        initialCounter: Long,
        segmentSize: Long,
        ciphertext: ByteArray,
    ): ByteArray {
        if (ciphertext.isEmpty()) return ByteArray(0)
        if (segmentSize <= 0L) {
            return chacha20Once(key, nonce, initialCounter, ciphertext)
        }
        val seg = segmentSize.toInt()
        val out = ByteArray(ciphertext.size)
        var offset = 0
        while (offset < ciphertext.size) {
            val end = minOf(offset + seg, ciphertext.size)
            val chunk = ciphertext.copyOfRange(offset, end)
            // Segmented Note files reset the block counter to 0 per segment.
            val plain = chacha20Once(key, nonce, 0L, chunk)
            plain.copyInto(out, offset)
            offset = end
        }
        return out
    }

    /** Build a minimal RSA-wrapped PLAUD.AI file for tests / fake peripherals. */
    fun encryptFile(
        publicKeyPem: String,
        plaintextBody: ByteArray,
        contentKey: ByteArray = ByteArray(32) { (it + 1).toByte() },
        nonce: ByteArray = ByteArray(12) { (it + 3).toByte() },
        userId: ByteArray = ByteArray(32) { '0'.code.toByte() },
    ): ByteArray {
        require(contentKey.size == 32)
        require(nonce.size == 12)
        val keyCipher = session.rsaEncryptPkcs1(publicKeyPem, contentKey)
        require(keyCipher.size == 256)
        val body = chacha20Once(contentKey, nonce, 1L, plaintextBody)
        val file = ByteArray(dev.velum.protocol.PlaudFile.HEADER_SIZE + body.size)
        dev.velum.protocol.PlaudFile.MAGIC.copyInto(file, 0)
        Bytes.u16Le(1).copyInto(file, dev.velum.protocol.PlaudFile.OFF_VERSION)
        Bytes.u16Le(512).copyInto(file, dev.velum.protocol.PlaudFile.OFF_HEADER_SIZE)
        userId.copyInto(file, dev.velum.protocol.PlaudFile.OFF_USER_ID, 0, minOf(32, userId.size))
        Bytes.u16Le(1).copyInto(file, dev.velum.protocol.PlaudFile.OFF_FILE_TYPE)
        Bytes.u16Le(1).copyInto(file, dev.velum.protocol.PlaudFile.OFF_CHANNEL)
        Bytes.u16Le(1).copyInto(file, dev.velum.protocol.PlaudFile.OFF_ENCRYPT_TYPE)
        Bytes.u32Le(1).copyInto(file, dev.velum.protocol.PlaudFile.OFF_DURATION)
        Bytes.u32Le(1).copyInto(file, dev.velum.protocol.PlaudFile.OFF_CHACHA_COUNTER)
        nonce.copyInto(file, dev.velum.protocol.PlaudFile.OFF_CHACHA_NONCE)
        Bytes.u32Le(0).copyInto(file, dev.velum.protocol.PlaudFile.OFF_SEGMENT_SIZE)
        keyCipher.copyInto(file, dev.velum.protocol.PlaudFile.OFF_KEY_CIPHER)
        body.copyInto(file, dev.velum.protocol.PlaudFile.HEADER_SIZE)
        return file
    }

    private fun chacha20Once(
        key: ByteArray,
        nonce: ByteArray,
        counter: Long,
        data: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance("ChaCha20")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "ChaCha20"),
            ChaCha20ParameterSpec(nonce, counter.toInt()),
        )
        // ENCRYPT_MODE xor stream == decrypt for a stream cipher.
        return cipher.doFinal(data)
    }
}

internal fun parsePrivateKey(pem: String): PrivateKey {
    val der = decodePem(pem, "PRIVATE KEY", "RSA PRIVATE KEY")
    val factory = KeyFactory.getInstance("RSA")
    return try {
        factory.generatePrivate(PKCS8EncodedKeySpec(der))
    } catch (_: Exception) {
        // PKCS#1 RSAPrivateKey → wrap as PKCS#8.
        val wrapped = wrapPkcs1PrivateKey(der)
        factory.generatePrivate(PKCS8EncodedKeySpec(wrapped))
    }
}

internal fun parsePublicKey(pem: String): PublicKey {
    val der = decodePem(pem, "PUBLIC KEY", "RSA PUBLIC KEY")
    val factory = KeyFactory.getInstance("RSA")
    return try {
        factory.generatePublic(X509EncodedKeySpec(der))
    } catch (_: Exception) {
        factory.generatePublic(X509EncodedKeySpec(wrapPkcs1PublicKey(der)))
    }
}

private fun decodePem(pem: String, vararg labels: String): ByteArray {
    var body = pem
    for (label in labels) {
        body = body
            .replace("-----BEGIN $label-----", "")
            .replace("-----END $label-----", "")
    }
    body = body.replace("\\s".toRegex(), "")
    return Base64.getDecoder().decode(body)
}

/** Minimal PKCS#8 wrap for PKCS#1 RSAPrivateKey DER. */
private fun wrapPkcs1PrivateKey(pkcs1: ByteArray): ByteArray {
    // PrivateKeyInfo ::= SEQUENCE { version, algId, octetString }
    val algId = byteArrayOf(
        0x30, 0x0D,
        0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x01,
        0x05, 0x00,
    )
    val version = byteArrayOf(0x02, 0x01, 0x00)
    val octet = derOctetString(pkcs1)
    val seq = version + algId + octet
    return derSequence(seq)
}

private fun wrapPkcs1PublicKey(pkcs1: ByteArray): ByteArray {
    val algId = byteArrayOf(
        0x30, 0x0D,
        0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x01,
        0x05, 0x00,
    )
    val bitString = byteArrayOf(0x03) + derLength(pkcs1.size + 1) + byteArrayOf(0x00) + pkcs1
    return derSequence(algId + bitString)
}

private fun derOctetString(data: ByteArray): ByteArray =
    byteArrayOf(0x04) + derLength(data.size) + data

private fun derSequence(content: ByteArray): ByteArray =
    byteArrayOf(0x30) + derLength(content.size) + content

private fun derLength(len: Int): ByteArray = when {
    len < 0x80 -> byteArrayOf(len.toByte())
    len < 0x100 -> byteArrayOf(0x81.toByte(), len.toByte())
    else -> byteArrayOf(0x82.toByte(), (len ushr 8).toByte(), len.toByte())
}

fun rsaKeyPairToPem(): Pair<String, String> {
    val kpg = java.security.KeyPairGenerator.getInstance("RSA")
    kpg.initialize(2048)
    val pair = kpg.generateKeyPair()
    val publicPem = encodePem("PUBLIC KEY", pair.public.encoded)
    val privatePem = encodePem("PRIVATE KEY", pair.private.encoded)
    return publicPem to privatePem
}

fun encodePem(label: String, der: ByteArray): String {
    val b64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der)
    return "-----BEGIN $label-----\n$b64\n-----END $label-----"
}
