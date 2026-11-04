package dev.velum.app.platform

// No app-owned cache copies of incoming files on this platform: nothing to delete.
actual fun isImportScratchPath(absolutePath: String): Boolean = false
