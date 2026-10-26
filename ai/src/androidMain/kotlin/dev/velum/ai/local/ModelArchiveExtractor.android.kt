package dev.velum.ai.local

import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

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
        val src = File(archiveFile.absolutePath)
        require(src.isFile && src.length() > 0L) {
            "Archive missing or empty: ${archiveFile.absolutePath}"
        }
        val dest = File(destDir.absolutePath)
        dest.mkdirs()
        val destCanon = dest.canonicalFile
        openArchiveStream(src).use { archive ->
            var entry: ArchiveEntry? = archive.nextEntry
            while (entry != null) {
                val outFile = File(dest, entry.name)
                val outCanon = outFile.canonicalFile
                if (!outCanon.path.startsWith(destCanon.path + File.separator) &&
                    outCanon != destCanon
                ) {
                    throw IllegalStateException(
                        "Refusing archive entry outside dest (zip-slip): ${entry.name}",
                    )
                }
                if (entry.isDirectory) {
                    outCanon.mkdirs()
                } else {
                    outCanon.parentFile?.mkdirs()
                    outCanon.outputStream().use { output ->
                        archive.copyTo(output)
                    }
                }
                entry = archive.nextEntry
            }
        }
    }

    private fun openArchiveStream(src: File): ArchiveInputStream<out ArchiveEntry> {
        val name = src.name.lowercase()
        val raw: InputStream = BufferedInputStream(FileInputStream(src))
        return when {
            name.endsWith(".zip") -> ZipArchiveInputStream(raw)
            name.endsWith(".tar.bz2") || name.endsWith(".tbz2") ->
                TarArchiveInputStream(BZip2CompressorInputStream(raw))
            name.endsWith(".tar.gz") || name.endsWith(".tgz") ->
                TarArchiveInputStream(GzipCompressorInputStream(raw))
            name.endsWith(".tar") -> TarArchiveInputStream(raw)
            else -> {
                raw.close()
                throw IllegalArgumentException("Unsupported archive type: ${src.name}")
            }
        }
    }
}
