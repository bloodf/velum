package dev.velum.app.platform

/** Absolute directory for phone mic captures (WAV). */
expect fun recordingsDirectoryPath(): String

/** Best-effort delete of a local audio file (privacy wipe). */
expect fun deleteFileAtPath(absolutePath: String): Boolean

/** True when both paths name the same file (canonical paths: `..`, symlinks and spelling do not matter). */
expect fun isSamePath(a: String, b: String): Boolean

/**
 * True only when [previousPath] is this recording's own older audio (`<id>.*`) directly in [dir] and is
 * not [newPath]. Compares canonical paths so `..`, symlinks or a different dir spelling never match.
 */
expect fun isSupersededOwnAudio(previousPath: String, newPath: String, dir: String, id: String): Boolean
