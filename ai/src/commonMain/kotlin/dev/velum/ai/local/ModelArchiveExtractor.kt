package dev.velum.ai.local

/**
 * Extract downloaded model archives into [destDir].
 * Android/JVM use Apache commons-compress; iOS streams catalog tar archives through system bzip2/zlib.
 */
expect object ModelArchiveExtractor {
    fun isArchive(fileName: String): Boolean

    /**
     * Extract [archiveFile] into [destDir] (created if needed).
     * Rejects path-traversal entries (zip-slip).
     */
    fun extract(archiveFile: ModelStoreDir, destDir: ModelStoreDir)
}
