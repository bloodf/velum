package dev.velum.core.security

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.Properties
import javax.crypto.SecretKey
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

/**
 * Desktop secure store: AES-256-GCM ciphertext in a properties file.
 *
 * The software AES key lives in a sibling file (not Android Keystore — that is
 * Android-only / non-exportable). Identity values must never appear as plaintext
 * in the properties file.
 */
class DesktopSecureStore(
    private val file: Path = Path.of(System.getProperty("user.home"), ".velum", "secure.properties"),
    private val keyFile: Path = file.resolveSibling("secure.aes256"),
) : SecureStore {
    private val key: SecretKey = loadOrCreateKey(keyFile)
    private val backend = linkedMapOf<String, String>()
    private val encrypted = JvmAesGcmSecureStore(key = key, backend = backend)

    init {
        if (file.exists()) {
            val props = Properties()
            file.inputStream().use { props.load(it) }
            props.stringPropertyNames().forEach { name ->
                backend[name] = props.getProperty(name)
            }
        }
    }

    private fun flush() {
        Files.createDirectories(file.parent)
        val props = Properties()
        backend.forEach { (k, v) -> props.setProperty(k, v) }
        file.outputStream().use { props.store(it, "Velum AES-GCM ciphertext — do not commit") }
    }

    override suspend fun putString(key: String, value: String) {
        encrypted.putString(key, value)
        flush()
    }

    override suspend fun getString(key: String): String? {
        val hadEntry = backend.containsKey(key)
        val value = encrypted.getString(key)
        // Corrupt ciphertext is dropped inside [encrypted]; persist the removal.
        if (hadEntry && !backend.containsKey(key)) {
            flush()
        }
        return value
    }

    override suspend fun remove(key: String) {
        encrypted.remove(key)
        flush()
    }

    override suspend fun contains(key: String): Boolean = encrypted.contains(key)

    override suspend fun wipe() {
        encrypted.wipe()
        flush()
    }

    companion object {
        private fun loadOrCreateKey(path: Path): SecretKey {
            if (path.exists()) {
                val bytes = Files.readAllBytes(path)
                return JvmAesGcmSecureStore.keyFromBytes(bytes)
            }
            Files.createDirectories(path.parent)
            val generated = JvmAesGcmSecureStore.generateAes256Key()
            Files.write(path, generated.encoded)
            runCatching {
                Files.setPosixFilePermissions(
                    path,
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                )
            }
            return generated
        }
    }
}
