package dev.velum.app.platform

import java.io.File

private val scratchNames = listOf("imports", "share")

actual fun isImportScratchPath(absolutePath: String): Boolean {
    val cache = appContextOrNull()?.cacheDir ?: return false
    return runCatching {
        val file = File(absolutePath).canonicalFile
        scratchNames.any { name -> file.path.startsWith(File(cache, name).canonicalPath + File.separator) }
    }.getOrDefault(false)
}

actual fun wipeImportScratch() {
    val cache = appContextOrNull()?.cacheDir ?: return
    scratchNames.forEach { runCatching { File(cache, it).deleteRecursively() } }
}
