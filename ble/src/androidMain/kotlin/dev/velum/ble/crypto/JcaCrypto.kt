package dev.velum.ble.crypto

import dev.velum.protocol.Bytes
import dev.velum.protocol.ChaCha20
import dev.velum.protocol.ChaCha20Poly1305
import dev.velum.protocol.FileDecryptor
import dev.velum.protocol.Protocol20SessionSecret
import dev.velum.protocol.SessionCipher
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.security.PublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

actual fun createSessionCipher(): SessionCipher = JcaSessionCipher()

actual fun createFileDecryptor(): FileDecryptor = JcaFileDecryptor()

/**
 * ChaCha20-Poly1305 names to try, in order. Android documents "ChaCha20/Poly1305/NoPadding" (API 28+) and it is the
 * only one that resolves on API 28 AOSP images, where the bare alias throws NoSuchAlgorithmException. A JDK (host
 * unit tests) only registers the alias. The first one that passes the known-answer probe is used for seal and open.
 */
private val PLATFORM_CHACHA_TRANSFORMATIONS = listOf("ChaCha20/Poly1305/NoPadding", "ChaCha20-Poly1305")

/**
 * JCA-backed protocol-20 crypto (ChaCha20-Poly1305 + RSA PKCS#1 v1.5).
 */
class JcaSessionCipher : SessionCipher {
    /**
     * Android documents ChaCha20/Poly1305 from API 28, and minSdk is 26 (#226); API 28 only resolves the full transformation name. Without a usable platform cipher,
     * use the Kotlin AEAD, which has the same output. Probed per instance, not cached globally. The probe seals the
     * RFC 8439 section 2.8.2 message with the platform cipher and compares it with the fixed sealed bytes (not with
     * the Kotlin code, so a bug there cannot discard a correct platform cipher). A provider that is missing, throws
     * (any [GeneralSecurityException] or [ProviderException]) or ignores the IV gets the Kotlin path instead of
     * failing every real call. Not logged here (plain JUnit tests build this class and `android.util.Log` is not
     * mocked); read [usesPlatformChaCha] to see the path.
     */
    private val platformTransformation: String? = PLATFORM_CHACHA_TRANSFORMATIONS.firstOrNull { probe(it) }

    val usesPlatformChaCha: Boolean = platformTransformation != null

    private fun probe(transformation: String): Boolean = try {
        val cipher = Cipher.getInstance(transformation)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(PROBE_KEY, "ChaCha20"), IvParameterSpec(PROBE_NONCE))
        cipher.updateAAD(PROBE_AAD)
        cipher.doFinal(PROBE_PLAIN).contentEquals(PROBE_SEALED)
    } catch (_: GeneralSecurityException) {
        // NoSuchAlgorithm / NoSuchPadding / InvalidKey / InvalidAlgorithmParameter: no usable cipher.
        false
    } catch (_: ProviderException) {
        false
    }

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
        // Not left to the provider: what it throws for this differs (see AesGcmWifiFrameCipher.open).
        if (ciphertextAndTag.size < AEAD_TAG_BYTES) return null
        val transformation = platformTransformation ?: return ChaCha20Poly1305.open(key, nonce, aad, ciphertextAndTag)
        return try {
            val cipher = Cipher.getInstance(transformation)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "ChaCha20"),
                IvParameterSpec(nonce),
            )
            cipher.updateAAD(aad)
            cipher.doFinal(ciphertextAndTag)
        } catch (_: BadPaddingException) {
            // A bad tag. Anything else, like a missing algorithm, isn't an auth failure, so it throws for the caller.
            null
        }
    }

    override fun chacha20Poly1305Seal(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        plaintext: ByteArray,
    ): ByteArray {
        val transformation = platformTransformation ?: return ChaCha20Poly1305.seal(key, nonce, aad, plaintext)
        val cipher = Cipher.getInstance(transformation)
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
            return ChaCha20.xor(key, nonce, initialCounter, ciphertext)
        }
        val seg = segmentSize.toInt()
        val out = ByteArray(ciphertext.size)
        var offset = 0
        while (offset < ciphertext.size) {
            val end = minOf(offset + seg, ciphertext.size)
            val chunk = ciphertext.copyOfRange(offset, end)
            // Segmented Note files reset the block counter to 0 per segment.
            val plain = ChaCha20.xor(key, nonce, 0L, chunk)
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
        val body = ChaCha20.xor(contentKey, nonce, 1L, plaintextBody)
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

private fun hexBytes(s: String): ByteArray {
    val h = s.filter { !it.isWhitespace() }
    return ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

// RFC 8439 section 2.8.2, used as the known-answer probe for the platform cipher.
private val PROBE_KEY = ByteArray(32) { (0x80 + it).toByte() }
private val PROBE_NONCE = hexBytes("070000004041424344454647")
private val PROBE_AAD = hexBytes("50515253c0c1c2c3c4c5c6c7")
private val PROBE_PLAIN = (
    "Ladies and Gentlemen of the class of '99: If I could offer you only one tip " +
        "for the future, sunscreen would be it."
    ).encodeToByteArray()
private val PROBE_SEALED = hexBytes(
    "d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d6" +
        "3dbea45e8ca9671282fafb69da92728b1a71de0a9e060b2905d6a5b67ecd3b36" +
        "92ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc" +
        "3ff4def08e4b7a9de576d26586cec64b61161ae10b594f09e26a7e902ecbd0600691",
)

private const val AEAD_TAG_BYTES = 16
