package dev.velum.app.platform

import java.io.File

actual fun isImportScratchPath(absolutePath: String): Boolean {
    val cache = appContextOrNull()?.cacheDir ?: return false
    return runCatching {
        File(absolutePath).canonicalFile.path.startsWith(File(cache, "imports").canonicalPath + File.separator)
    }.getOrDefault(false)
}

actual fun deleteImportResidue(recordingsDir: String) {
    File(recordingsDir).listFiles().orEmpty()
        .filter { it.isFile && (it.name.startsWith("import_") || it.name.endsWith(".tmp") || it.name.endsWith(".part")) }
        .forEach { runCatching { it.delete() } }
}
