package dev.velum.app.platform

/** Absolute directory for phone mic captures (WAV). */
expect fun recordingsDirectoryPath(): String

/** Write [bytes] to an absolute file path, creating parent dirs as needed. */
expect fun writeBytesToPath(absolutePath: String, bytes: ByteArray)

/**
 * Write [bytes] via a sibling temp file, then rename over [absolutePath].
 * On failure the destination (if any) is left untouched and the temp is removed (#113 / #86).
 *
 * Optional [beforeRename] runs after the temp is fully written — tests inject failures here.
 */
expect fun writeBytesAtomically(
    absolutePath: String,
    bytes: ByteArray,
    beforeRename: () -> Unit = {},
)

/** Best-effort delete of a local audio file (privacy wipe). */
expect fun deleteFileAtPath(absolutePath: String): Boolean
