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

/** True when no file remains at [absolutePath] afterwards, including when there was none. */
expect fun deleteFileIfPresent(absolutePath: String): Boolean

/**
 * Deletes the recordings folder and all it holds, including audio no row points at (a crash before the row was
 * written, a mic's `.partial`, the orphan quarantine). False if some remains. Only once nothing can be writing there.
 */
expect fun deleteRecordingsDirectory(): Boolean

/** Deletes temp files of device transfers a dead process left behind. False if some remain. */
expect fun deleteDeviceTransferTemps(): Boolean
