package dev.velum.app.platform

actual fun recordingsDirectoryPath(): String = "/tmp/velum-recordings"

actual fun writeBytesToPath(absolutePath: String, bytes: ByteArray) {
    // iOS host is out of scope for 1.0; keep a no-op-safe write via Kotlin/Native later.
    error("writeBytesToPath is Android-first; iOS file IO lands with M3")
}

actual fun deleteFileAtPath(absolutePath: String): Boolean = false
