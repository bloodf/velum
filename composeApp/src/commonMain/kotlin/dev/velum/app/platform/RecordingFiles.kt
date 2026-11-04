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

/** True for audio the app itself wrote: its recordings folder and its import cache. Never a file the user picked in place. */
expect fun isAppOwnedAudioPath(absolutePath: String): Boolean

/** True only for a source copy the app made in its import cache, never for its recordings or normalized audio. */
expect fun isImportCacheCopy(absolutePath: String): Boolean
