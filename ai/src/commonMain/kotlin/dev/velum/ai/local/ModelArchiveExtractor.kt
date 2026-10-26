package dev.velum.ai.local

/**
 * Extract downloaded model archives into [destDir].
 * Android/JVM use Apache commons-compress; iOS is unsupported.
 */
expect object ModelArchiveExtractor {
    fun isArchive(fileName: String): Boolean

    /**
     * Extract [archiveFile] into [destDir] (created if needed).
     * Rejects path-traversal entries (zip-slip).
     */
    fun extract(archiveFile: ModelStoreDir, destDir: ModelStoreDir)
}
