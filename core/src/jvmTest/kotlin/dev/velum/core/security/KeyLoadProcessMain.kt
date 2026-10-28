package dev.velum.core.security

import java.nio.file.Path

/**
 * Tiny process entry for cross-JVM key-load race tests.
 * Args: `<keyFilePath>` — prints lowercase hex of the loaded AES-256 key to stdout.
 */
fun main(args: Array<String>) {
    require(args.size == 1) { "usage: KeyLoadProcessMain <keyFile>" }
    val key = DesktopSecureStore.loadOrCreateKey(Path.of(args[0]))
    val hex = key.encoded.joinToString("") { b -> "%02x".format(b) }
    println(hex)
}
