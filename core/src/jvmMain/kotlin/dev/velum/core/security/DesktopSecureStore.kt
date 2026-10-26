package dev.velum.core.security

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

/**
 * Desktop foundation store: preferences file under the user home directory.
 * Not a substitute for OS keyring; M2 should use libsecret / DPAPI / Keychain.
 */
class DesktopSecureStore(
    private val file: Path = Path.of(System.getProperty("user.home"), ".velum", "secure.properties"),
) : SecureStore {
    private val props = Properties()

    init {
        if (file.exists()) {
            file.inputStream().use { props.load(it) }
        }
    }

    private fun flush() {
        Files.createDirectories(file.parent)
        file.outputStream().use { props.store(it, "Velum secure settings — do not commit") }
    }

    override suspend fun putString(key: String, value: String) {
        synchronized(props) {
            props.setProperty(key, value)
            flush()
        }
    }

    override suspend fun getString(key: String): String? =
        synchronized(props) { props.getProperty(key) }

    override suspend fun remove(key: String) {
        synchronized(props) {
            props.remove(key)
            flush()
        }
    }

    override suspend fun contains(key: String): Boolean =
        synchronized(props) { props.containsKey(key) }
}
