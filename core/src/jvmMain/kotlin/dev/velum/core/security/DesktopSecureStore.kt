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
 * Key load/create runs under [AesKeyFileLock] (cross-process file lock + same-JVM
 * monitor). New keys are published whole (temp + fsync + hard link / no-replace
 * move) so readers never see a partial `secure.aes256`. A wrong-length key file
 * is quarantined then replaced; existing ciphertext entries are kept.
 */
class DesktopSecureStore private constructor(
    private val file: Path,
    private val keyFile: Path,
    private val onOwnerOnlyTempCreated: ((Path) -> Unit)?,
) : SecureStore {
    /**
     * Production constructor — no test hooks.
     */
    constructor(
        file: Path = Path.of(System.getProperty("user.home"), ".velum", "secure.properties"),
        keyFile: Path = file.resolveSibling("secure.aes256"),
    ) : this(file, keyFile, onOwnerOnlyTempCreated = null)

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
        /**
         * Test-only factory: same as the public constructor plus an owner-only
         * temp creation probe (not reachable from production call sites).
         */
        internal fun createForTest(
            file: Path,
            keyFile: Path,
            onOwnerOnlyTempCreated: ((Path) -> Unit)? = null,
        ): DesktopSecureStore = DesktopSecureStore(file, keyFile, onOwnerOnlyTempCreated)

        /** Read a valid AES-256 key from [path], or null if missing/wrong length/raced away. */
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
         * new key. Entire critical section runs under [AesKeyFileLock].
         *
         * @param afterKeyTempReady test hook after durable temp write, before publish
         */
        internal fun loadOrCreateKey(
            path: Path,
            afterKeyTempReady: (() -> Unit)? = null,
        ): SecretKey = AesKeyFileLock.withLock(path) {
            loadOrCreateKeyLocked(path, afterKeyTempReady)
        }

        private fun loadOrCreateKeyLocked(
            path: Path,
            afterKeyTempReady: (() -> Unit)?,
        ): SecretKey {
            readValidKeyBytes(path)?.let { return JvmAesGcmSecureStore.keyFromBytes(it) }

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
                        quarantineToBadSibling(path)
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
                publishOwnerOnlyBytesExclusive(
                    target = path,
                    bytes = generated.encoded,
                    afterTempReady = afterKeyTempReady,
                )
                generated
            } catch (_: FileAlreadyExistsException) {
                // Exclusive publish saw an existing target; re-read under the lock
                // and mint only if the key is still absent or was quarantined.
                readValidKeyBytes(path)?.let {
                    return JvmAesGcmSecureStore.keyFromBytes(it)
                }
                if (path.exists()) {
                    val leftover = try {
                        Files.readAllBytes(path)
                    } catch (_: NoSuchFileException) {
                        null
                    }
                    if (leftover != null && leftover.size != AES_256_KEY_BYTES) {
                        quarantineToBadSibling(path)
                    }
                }
                readValidKeyBytes(path)?.let {
                    return JvmAesGcmSecureStore.keyFromBytes(it)
                }
                val minted = JvmAesGcmSecureStore.generateAes256Key()
                publishOwnerOnlyBytesExclusive(
                    target = path,
                    bytes = minted.encoded,
                    afterTempReady = afterKeyTempReady,
                )
                minted
            }
        }
    }
}
