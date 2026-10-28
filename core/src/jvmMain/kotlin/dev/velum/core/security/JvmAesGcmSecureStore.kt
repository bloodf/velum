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
 */
class JvmAesGcmSecureStore(
    private val key: SecretKey = generateAes256Key(),
    private val backend: MutableMap<String, String> = linkedMapOf(),
) : SecureStore {
    private val mutex = Mutex()

    /** Raw ciphertext entries for inspection tests — never decrypts. */
    fun rawCiphertextSnapshot(): Map<String, String> = synchronized(backend) { backend.toMap() }

    /** Test helper: write an arbitrary blob (e.g. corrupt ciphertext) under [keyName]. */
    fun putRawCiphertextForTest(keyName: String, raw: String) {
        synchronized(backend) { backend[keyName] = raw }
    }

    override suspend fun putString(key: String, value: String) {
        mutex.withLock {
            val encrypted = AesGcmSecurePayload.encrypt(this.key, value.toByteArray(Charsets.UTF_8))
            synchronized(backend) { backend[key] = encrypted }
        }
    }

    override suspend fun getString(key: String): String? = mutex.withLock {
        val stored = synchronized(backend) { backend[key] } ?: return@withLock null
        runCatching {
            AesGcmSecurePayload.decrypt(this.key, stored).toString(Charsets.UTF_8)
        }.getOrElse {
            // Corrupt or wrong-key blob — drop and treat as missing. Never echo payload.
            synchronized(backend) { backend.remove(key) }
            null
        }
    }

    override suspend fun remove(key: String) {
        mutex.withLock {
            synchronized(backend) { backend.remove(key) }
        }
    }

    override suspend fun contains(key: String): Boolean = mutex.withLock {
        synchronized(backend) { backend.containsKey(key) }
    }

    override suspend fun wipe() {
        mutex.withLock {
            synchronized(backend) { backend.clear() }
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
