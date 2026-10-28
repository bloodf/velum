package dev.velum.core.security

import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * JVM/desktop AES-256-GCM secure store (software key).
 *
 * Mirrors the Android Keystore-backed ciphertext layout so regression tests can
 * prove identity values never appear as plaintext in the persistence backend.
 * The software key is exportable on the JVM — Android Keystore non-exportable
 * keys cannot be covered here (see test notes).
 *
 * Inject [backend] from tests to inspect raw ciphertext without production
 * test-only helpers.
 */
class JvmAesGcmSecureStore(
    private val key: SecretKey = generateAes256Key(),
    private val backend: MutableMap<String, String> = linkedMapOf(),
) : SecureStore {
    private val mutex = Mutex()

    override suspend fun putString(key: String, value: String) {
        mutex.withLock {
            val encrypted = AesGcmSecurePayload.encrypt(this.key, value.toByteArray(Charsets.UTF_8))
            backend[key] = encrypted
        }
    }

    override suspend fun getString(key: String): String? = mutex.withLock {
        val stored = backend[key] ?: return@withLock null
        when (val result = AesGcmSecurePayload.decryptResult(this.key, stored)) {
            is AesGcmDecryptResult.Ok ->
                result.plaintext.toString(Charsets.UTF_8)
            AesGcmDecryptResult.StructurallyMalformed -> {
                // Only drop blobs that are not valid vault payloads.
                backend.remove(key)
                null
            }
            AesGcmDecryptResult.CryptographicFailure -> {
                // Wrong key / auth failure — keep ciphertext so a restored key can open it.
                null
            }
        }
    }

    override suspend fun read(key: String): SecureRead = mutex.withLock {
        val stored = backend[key] ?: return@withLock SecureRead.Missing
        when (val result = AesGcmSecurePayload.decryptResult(this.key, stored)) {
            is AesGcmDecryptResult.Ok ->
                SecureRead.Value(result.plaintext.toString(Charsets.UTF_8))
            AesGcmDecryptResult.StructurallyMalformed ->
                SecureRead.Unreadable(AesGcmDecryptResult.StructurallyMalformed::class.java.name)
            AesGcmDecryptResult.CryptographicFailure ->
                SecureRead.Unreadable(AesGcmDecryptResult.CryptographicFailure::class.java.name)
        }
    }

    override suspend fun remove(key: String) {
        mutex.withLock {
            backend.remove(key)
        }
    }

    override suspend fun contains(key: String): Boolean = mutex.withLock {
        backend.containsKey(key)
    }

    override suspend fun wipe() {
        mutex.withLock {
            backend.clear()
        }
    }

    companion object {
        fun generateAes256Key(): SecretKey {
            val generator = KeyGenerator.getInstance("AES")
            generator.init(256, SecureRandom())
            return generator.generateKey()
        }

        fun keyFromBytes(bytes: ByteArray): SecretKey {
            require(bytes.size == 32) { "AES-256 key must be 32 bytes" }
            return SecretKeySpec(bytes.copyOf(), "AES")
        }
    }
}
