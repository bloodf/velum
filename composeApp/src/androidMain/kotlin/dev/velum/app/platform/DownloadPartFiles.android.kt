package dev.velum.app.platform

import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

private val PART_NAME = Regex("^rec-[A-Za-z0-9_-]+\\.part$")

actual fun streamToFileAtomically(
    partPath: String,
    headBytes: Int,
    finalPathFor: (head: ByteArray) -> String,
    produce: (sink: (ByteArray) -> Unit) -> Unit,
): Pair<String, Long> {
    val part = File(partPath)
    part.parentFile?.mkdirs()
    val head = ByteArray(headBytes)
    var headLen = 0
    var size = 0L
    try {
        FileOutputStream(part).use { out ->
            produce { chunk ->
                val n = minOf(headBytes - headLen, chunk.size)
                chunk.copyInto(head, headLen, 0, n)
                headLen += n
                out.write(chunk)
                size += chunk.size
            }
            out.fd.sync()
        }
        val finalPath = finalPathFor(head.copyOf(headLen))
        try {
            Files.move(
                part.toPath(),
                File(finalPath).toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            // Not atomic: may briefly leave neither file. Only reached where ATOMIC_MOVE is
            // unsupported, which a same-directory rename on ext4/f2fs never is.
            Files.move(part.toPath(), File(finalPath).toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        syncDirectory(part.absoluteFile.parentFile)
        return finalPath to size
    } catch (t: Throwable) {
        part.delete()
        throw t
    }
}

/** Persist the rename: Linux/Android fsync a directory fd; platforms that refuse it are best effort. */
internal fun syncDirectory(dir: File?) {
    if (dir == null) return
    runCatching { FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) } }
}

actual fun sweepStaleDownloadParts(dirPath: String, olderThanEpochMs: Long): List<String> =
    File(dirPath).listFiles().orEmpty()
        .filter { it.isFile && PART_NAME.matches(it.name) && it.lastModified() < olderThanEpochMs }
        .filter { it.delete() }
        .map { it.name }
