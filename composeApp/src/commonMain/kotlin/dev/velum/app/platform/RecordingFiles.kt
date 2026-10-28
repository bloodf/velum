package dev.velum.app.platform

/** Absolute directory for phone mic captures (WAV). */
expect fun recordingsDirectoryPath(): String

/** Write [bytes] to an absolute file path, creating parent dirs as needed. */
expect fun writeBytesToPath(absolutePath: String, bytes: ByteArray)
