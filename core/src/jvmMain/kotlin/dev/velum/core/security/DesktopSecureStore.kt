package dev.velum.core.security

import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
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
 * creation, no-replace create so concurrent starters converge on one key). A key
 * file of the wrong length is quarantined then treated like a missing key: a new
 * key is generated and existing ciphertext entries are kept.
 *
 * @param onOwnerOnlyTempCreated optional test hook invoked when an owner-only
 * temp file is created during key/properties writes (before any bytes are written).
 */
class DesktopSecureStore(
    private val file: Path = Path.of(System.getProperty("user.home"), ".velum", "secure.properties"),
    private val keyFile: Path = file.resolveSibling("secure.aes256"),
    private val onOwnerOnlyTempCreated: ((Path) -> Unit)? = null,
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
            onTempCreated = onOwnerOnlyTempCreated,
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

        /** Read a valid 32-byte AES key from [path], or null if missing/wrong length/raced away. */
        internal fun readValidKeyBytes(path: Path): ByteArray? {
            if (!path.exists()) return null
            val bytes = try {
                Files.readAllBytes(path)
            } catch (_: NoSuchFileException) {
                return null
            }
            return bytes.takeIf { it.size == AES_256_KEY_BYTES }
        }

        /**
         * Load an existing AES-256 key, quarantine a wrong-length file, or mint a
         * new key with a CREATE_NEW write so concurrent starters share one key.
         */
        internal fun loadOrCreateKey(path: Path): SecretKey {
            readValidKeyBytes(path)?.let { return JvmAesGcmSecureStore.keyFromBytes(it) }

            // Wrong-length file: quarantine only if it is *still* wrong-length.
            // A concurrent starter may have already minted a valid key at [path].
            if (path.exists()) {
                val bytes = try {
                    Files.readAllBytes(path)
                } catch (_: NoSuchFileException) {
                    null
                }
                when {
                    bytes == null -> Unit
                    bytes.size == AES_256_KEY_BYTES ->
                        return JvmAesGcmSecureStore.keyFromBytes(bytes)
                    else -> {
                        // Re-check immediately before quarantine to avoid moving a
                        // newly minted valid key that appeared after our first read.
                        readValidKeyBytes(path)?.let {
                            return JvmAesGcmSecureStore.keyFromBytes(it)
                        }
                        val stillBad = try {
                            Files.readAllBytes(path)
                        } catch (_: NoSuchFileException) {
                            null
                        }
                        if (stillBad != null && stillBad.size != AES_256_KEY_BYTES) {
                            quarantineToBadSibling(path)
                        }
                        readValidKeyBytes(path)?.let {
                            return JvmAesGcmSecureStore.keyFromBytes(it)
                        }
                    }
                }
            }

            Files.createDirectories(path.parent)
            readValidKeyBytes(path)?.let { return JvmAesGcmSecureStore.keyFromBytes(it) }

            val generated = JvmAesGcmSecureStore.generateAes256Key()
            return try {
                // CREATE_NEW: never replace a key another process just wrote.
                createOwnerOnlyBytesExclusive(path, generated.encoded)
                generated
            } catch (_: FileAlreadyExistsException) {
                // Winner may still be writing — wait briefly for a valid 32-byte key.
                repeat(100) {
                    readValidKeyBytes(path)?.let {
                        return JvmAesGcmSecureStore.keyFromBytes(it)
                    }
                    Thread.sleep(1)
                }
                val winner = readValidKeyBytes(path)
                    ?: error("key file appeared but is not a valid AES-256 key")
                JvmAesGcmSecureStore.keyFromBytes(winner)
            }
        }
    }
}
