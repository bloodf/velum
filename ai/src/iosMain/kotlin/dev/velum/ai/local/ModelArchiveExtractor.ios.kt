package dev.velum.ai.local

actual object ModelArchiveExtractor {
    actual fun isArchive(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return lower.endsWith(".zip") ||
            lower.endsWith(".tar") ||
            lower.endsWith(".tar.gz") ||
            lower.endsWith(".tgz") ||
            lower.endsWith(".tar.bz2") ||
            lower.endsWith(".tbz2")
    }

    actual fun extract(archiveFile: ModelStoreDir, destDir: ModelStoreDir) {
        throw UnsupportedOperationException(
            "Model archive extract is Android/JVM-only for now. " +
                "archive=${archiveFile.absolutePath}",
        )
    }
}
