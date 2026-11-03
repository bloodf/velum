package dev.velum.app.platform

/**
 * True for a temporary copy the app made of an incoming file (picker, share sheet, watched folder) in its cache.
 * Only such a copy may be deleted after an import; a path the user chose must never be.
 */
expect fun isImportScratchPath(absolutePath: String): Boolean

/** Deletes every leftover incoming-file copy (privacy wipe). */
expect fun wipeImportScratch()
