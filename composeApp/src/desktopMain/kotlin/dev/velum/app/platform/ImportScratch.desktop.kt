package dev.velum.app.platform

// No app-owned cache copies of incoming files on this platform: nothing to delete.
actual fun isImportScratchPath(absolutePath: String): Boolean = false

// Desktop normalizes next to the source file, outside the recordings directory; nothing of ours is left in it.
actual fun deleteImportResidue(recordingsDir: String) = Unit
