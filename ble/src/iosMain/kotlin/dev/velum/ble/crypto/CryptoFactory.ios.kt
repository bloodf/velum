@file:OptIn(ExperimentalUnsignedTypes::class, dev.whyoleg.cryptography.DelicateCryptographyApi::class)

package dev.velum.ble.crypto

import com.ionspin.kotlin.crypto.LibsodiumInitializer
import com.ionspin.kotlin.crypto.aead.AeadCorrupedOrTamperedDataException
import com.ionspin.kotlin.crypto.aead.AuthenticatedEncryptionWithAssociatedData
import com.ionspin.kotlin.crypto.stream.Stream
import dev.velum.protocol.Bytes
import dev.velum.protocol.FileDecryptor
import dev.velum.protocol.Protocol20SessionSecret
import dev.velum.protocol.SessionCipher
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.RSA
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.providers.apple.Apple

actual fun createSessionCipher(): SessionCipher = AppleSessionCipher()

actual fun createFileDecryptor(): FileDecryptor = AppleFileDecryptor()

private val sodiumReady: Boolean by lazy {
    // The native initializer invokes the callback synchronously after sodium_init.
    var ready = false
    LibsodiumInitializer.initializeWithCallback { ready = true }
    check(ready && LibsodiumInitializer.isInitialized())
    true
}

private fun validateChaCha(key: ByteArray, nonce: ByteArray) {
    require(key.size == 32) { "ChaCha20 key must be 32 bytes" }
    require(nonce.size == 12) { "ChaCha20 nonce must be 12 bytes" }
    check(sodiumReady)
}

private class AppleSessionCipher : SessionCipher {
    private val rsa = CryptographyProvider.Apple.get(RSA.PKCS1)

    override fun rsaDecryptPkcs1(privateKeyPem: String, ciphertext: ByteArray): ByteArray {
        val format = if (privateKeyPem.startsWith("-----BEGIN RSA PRIVATE KEY-----")) {
            RSA.PrivateKey.Format.PEM.PKCS1
        } else RSA.PrivateKey.Format.PEM
        return rsa.privateKeyDecoder(SHA256).decodeFromByteArrayBlocking(format, privateKeyPem.encodeToByteArray())
            .decryptor().decryptBlocking(ciphertext)
    }

    override fun rsaEncryptPkcs1(publicKeyPem: String, plaintext: ByteArray): ByteArray {
        val format = if (publicKeyPem.startsWith("-----BEGIN RSA PUBLIC KEY-----")) {
            RSA.PublicKey.Format.PEM.PKCS1
        } else RSA.PublicKey.Format.PEM
        return rsa.publicKeyDecoder(SHA256).decodeFromByteArrayBlocking(format, publicKeyPem.encodeToByteArray())
            .encryptor().encryptBlocking(plaintext)
    }

    override fun chacha20Poly1305Seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        validateChaCha(key, nonce)
        return AuthenticatedEncryptionWithAssociatedData.chaCha20Poly1305IetfEncrypt(
            plaintext.asUByteArray(), aad.asUByteArray(), nonce.asUByteArray(), key.asUByteArray(),
        ).asByteArray()
    }

    override fun chacha20Poly1305Open(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertextAndTag: ByteArray): ByteArray? {
        validateChaCha(key, nonce)
        if (ciphertextAndTag.size < 16) return null
        return try {
            AuthenticatedEncryptionWithAssociatedData.chaCha20Poly1305IetfDecrypt(
                ciphertextAndTag.asUByteArray(), aad.asUByteArray(), nonce.asUByteArray(), key.asUByteArray(),
            ).asByteArray()
        } catch (_: AeadCorrupedOrTamperedDataException) {
            null
        }
    }

    // Fixed session nonce is required by the device protocol, including Wi-Fi frames.
    override fun sealPacket(secret: Protocol20SessionSecret, sequence: Long, packet: ByteArray): ByteArray =
        chacha20Poly1305Seal(secret.key, secret.nonce, secret.aad, Bytes.u32Le(sequence) + packet)

    override fun openPacket(secret: Protocol20SessionSecret, sequence: Long, ciphertextAndTag: ByteArray): Pair<Long, ByteArray>? {
        val plain = chacha20Poly1305Open(secret.key, secret.nonce, secret.aad, ciphertextAndTag) ?: return null
        if (plain.size < 4 || Bytes.readU32Le(plain, 0) != sequence) return null
        return sequence to plain.copyOfRange(4, plain.size)
    }
}

private class AppleFileDecryptor : FileDecryptor {
    private val session = AppleSessionCipher()

    override fun unwrapContentKey(privateKeyPem: String, keyCipher: ByteArray): ByteArray =
        session.rsaDecryptPkcs1(privateKeyPem, keyCipher)

    override fun chacha20Decrypt(key: ByteArray, nonce: ByteArray, initialCounter: Long, segmentSize: Long, ciphertext: ByteArray): ByteArray {
        validateChaCha(key, nonce)
        require(initialCounter in 0..0xffffffffL) { "ChaCha20 counter must fit u32" }
        val out = ByteArray(ciphertext.size)
        var offset = 0
        while (offset < ciphertext.size) {
            val count = if (segmentSize > 0) minOf(segmentSize, (ciphertext.size - offset).toLong()).toInt() else ciphertext.size
            val counter = if (segmentSize > 0) 0L else initialCounter
            require(counter + (count.toLong() + 63) / 64 <= 0x100000000L) { "ChaCha20 counter overflow" }
            Stream.chacha20IetfXorIc(
                ciphertext.copyOfRange(offset, offset + count).asUByteArray(), nonce.asUByteArray(), counter.toUInt(), key.asUByteArray(),
            ).asByteArray().copyInto(out, offset)
            offset += count
        }
        return out
    }
}
