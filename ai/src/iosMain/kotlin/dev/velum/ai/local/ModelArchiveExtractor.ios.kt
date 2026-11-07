@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.velum.ai.local

import dev.velum.ai.compression.*
import kotlinx.cinterop.*
import platform.zlib.gzopen
import platform.zlib.gzread
import platform.zlib.gzclose

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
        val name = archiveFile.name.lowercase()
        require(archiveFile.exists() && archiveFile.length() > 0L) { "Archive missing or empty" }
        if (name.endsWith(".zip")) {
            IosZipArchive.extract(archiveFile, destDir)
        } else if (name.endsWith(".tar.bz2") || name.endsWith(".tbz2")) {
            val stream = requireNotNull(velum_bzopen(archiveFile.absolutePath)) { "Cannot open bzip2 archive" }
            try {
                TarModelArchive.extract({ buffer ->
                    val count = buffer.usePinned { velum_bzread(stream, it.addressOf(0), buffer.size) }
                    check(count >= 0) { "Invalid bzip2 archive" }
                    count
                }, destDir)
            } finally {
                velum_bzclose(stream)
            }
        } else {
            require(name.endsWith(".tar") || name.endsWith(".tar.gz") || name.endsWith(".tgz")) { "Unsupported model archive format" }
            val stream = requireNotNull(gzopen(archiveFile.absolutePath, "rb")) { "Cannot open model archive" }
            try {
                TarModelArchive.extract({ buffer ->
                    val count = buffer.usePinned { gzread(stream, it.addressOf(0), buffer.size.toUInt()) }
                    check(count >= 0) { "Invalid gzip archive" }
                    count
                }, destDir)
            } finally {
                gzclose(stream)
            }
        }
    }
}
