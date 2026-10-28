package dev.velum.core.security

import java.nio.file.Path
import java.security.MessageDigest

/**
 * Tiny process entry for cross-JVM key-load race tests.
 * Args: `<keyFilePath>` — prints lowercase hex SHA-256 of the loaded AES-256 key
 * (never the key bytes themselves).
 */
fun main(args: Array<String>) {
    require(args.size == 1) { "usage: KeyLoadProcessMain <keyFile>" }
    val key = DesktopSecureStore.loadOrCreateKey(Path.of(args[0]))
    val digest = MessageDigest.getInstance("SHA-256").digest(key.encoded)
    val hex = digest.joinToString("") { b -> "%02x".format(b) }
    println(hex)
}
