package dev.velum.ai.local

import java.security.MessageDigest

actual fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { b ->
        (b.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}
