package dev.velum.core.security

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import javax.crypto.SecretKey
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Desktop secure store: AES-256-GCM ciphertext in a properties file.
 *
 * The software AES key lives in a sibling file (not Android Keystore — that is
 * Android-only / non-exportable). Identity values must never appear as plaintext
 * in the properties file.
 *
 * All mutate + flush sequences share one [Mutex]; flushes snapshot the map and
 * write via temp file + atomic move.
 *
 * The AES key file is written through a temp file + atomic move (owner-only from
 * creation). A key file of the wrong length is treated like a missing key: a new
 * key is generated and existing ciphertext entries are kept.
 */
class DesktopSecureStore(
    private val file: Path = Path.of(System.getProperty("user.home"), ".velum", "secure.properties"),
    private val keyFile: Path = file.resolveSibling("secure.aes256"),
) : SecureStore {
    private val mutex = Mutex()
    private val key: SecretKey = loadOrCreateKey(keyFile)
    private val backend = linkedMapOf<String, String>()

    init {
        if (file.exists()) {
            val props = Properties()
            file.inputStream().use { props.load(it) }
            props.stringPropertyNames().forEach { name ->
                backend[name] = props.getProperty(name)
            }
        }
    }

    /** Snapshot + atomic write. Caller must hold [mutex]. */
    private fun flushLocked() {
        atomicWriteProperties(
            target = file,
            snapshot = backend.toMap(),
            comment = "Velum AES-GCM ciphertext — do not commit",
        )
    }

    override suspend fun putString(key: String, value: String) {
        mutex.withLock {
            backend[key] = AesGcmSecurePayload.encrypt(this.key, value.toByteArray(Charsets.UTF_8))
            flushLocked()
        }
    }

    override suspend fun getString(key: String): String? = mutex.withLock {
        val stored = backend[key] ?: return@withLock null
        when (val result = AesGcmSecurePayload.decryptResult(this.key, stored)) {
            is AesGcmDecryptResult.Ok ->
                result.plaintext.toString(Charsets.UTF_8)
            AesGcmDecryptResult.StructurallyMalformed -> {
                backend.remove(key)
                flushLocked()
                null
            }
            AesGcmDecryptResult.CryptographicFailure -> null
        }
    }

    override suspend fun remove(key: String) {
        mutex.withLock {
            backend.remove(key)
            flushLocked()
        }
    }

    override suspend fun contains(key: String): Boolean = mutex.withLock {
        backend.containsKey(key)
    }

    override suspend fun wipe() {
        mutex.withLock {
            backend.clear()
            flushLocked()
        }
    }

    companion object {
        private const val AES_256_KEY_BYTES = 32

        private fun loadOrCreateKey(path: Path): SecretKey {
            if (path.exists()) {
                val bytes = Files.readAllBytes(path)
                if (bytes.size == AES_256_KEY_BYTES) {
                    return JvmAesGcmSecureStore.keyFromBytes(bytes)
                }
                // Wrong length (e.g. crash between create and write) — treat as
                // missing: mint a new key, keep existing ciphertext entries.
            }
            Files.createDirectories(path.parent)
            val generated = JvmAesGcmSecureStore.generateAes256Key()
            atomicWriteOwnerOnlyBytes(path, generated.encoded)
            return generated
        }
    }
}
