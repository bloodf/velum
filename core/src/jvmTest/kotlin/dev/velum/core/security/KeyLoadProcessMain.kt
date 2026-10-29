package dev.velum.core.security

import java.nio.file.Files
import java.nio.file.Path

/**
 * Tiny process entry for cross-JVM key-load race tests.
 *
 * Args: `<keyFilePath>` — loads the AES key, checks the returned bytes equal
 * the on-disk file, prints only the ack token `ok`. Never prints key bytes or
 * key-derived digests.
 */
fun main(args: Array<String>) {
    require(args.size == 1) { "usage: KeyLoadProcessMain <keyFile>" }
    val keyFile = Path.of(args[0])
    val key = DesktopSecureStore.loadOrCreateKey(keyFile)
    val loaded = key.encoded
    val onDisk = Files.readAllBytes(keyFile)
    check(loaded != null && loaded.contentEquals(onDisk)) {
        "child JVM loaded key bytes differ from on-disk file (sizes ${loaded?.size} vs ${onDisk.size})"
    }
    println("ok")
}
