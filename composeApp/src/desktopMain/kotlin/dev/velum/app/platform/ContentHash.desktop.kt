package dev.velum.app.platform

import java.io.FileInputStream
import java.security.MessageDigest

actual fun sha256File(absolutePath: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(absolutePath).use { input ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            digest.update(buf, 0, n)
        }
    }
    return digest.digest().joinToString("") { b -> "%02x".format(b) }
}

actual fun sha256Bytes(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { b -> "%02x".format(b) }
