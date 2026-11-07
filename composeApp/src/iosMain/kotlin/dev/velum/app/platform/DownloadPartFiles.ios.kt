@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.velum.app.platform

import kotlinx.cinterop.*
import platform.Foundation.NSFileManager
import platform.posix.*

private val downloadPartName = Regex("^rec-[A-Za-z0-9_-]+\\.part$")

actual fun streamToFileAtomically(
    partPath: String,
    headBytes: Int,
    finalPathFor: (head: ByteArray) -> String,
    produce: (sink: (ByteArray) -> Unit) -> Unit,
): Pair<String, Long> {
    require(headBytes >= 0)
    val head = ByteArray(headBytes)
    val parent = partPath.substringBeforeLast('/', ".").ifEmpty { "/" }
    check(NSFileManager.defaultManager.createDirectoryAtPath(parent, true, null, null)) {
        "Cannot create download directory"
    }
    // O_EXCL also refuses symlinks and preserves a pre-existing part owned by another transfer.
    val fd = open(partPath, O_WRONLY or O_CREAT or O_EXCL or O_NOFOLLOW, 384)
    check(fd >= 0) { "Cannot create download part: errno=$errno" }
    var published = false
    var headLength = 0
    var size = 0L
    try {
        check(flock(fd, LOCK_EX or LOCK_NB) == 0) { "Cannot lock download part: errno=$errno" }
        produce { chunk ->
            val count = minOf(headBytes - headLength, chunk.size)
            chunk.copyInto(head, headLength, 0, count)
            headLength += count
            if (chunk.isNotEmpty()) chunk.usePinned { pinned ->
                var offset = 0
                while (offset < chunk.size) {
                    val written = write(fd, pinned.addressOf(offset), (chunk.size - offset).toULong())
                    if (written < 0 && errno == EINTR) continue
                    check(written > 0) { "Cannot write download part: errno=$errno" }
                    offset += written.toInt()
                }
            }
            size += chunk.size
        }
        check(fsync(fd) == 0) { "Cannot sync download part: errno=$errno" }
        memScoped {
            val info = alloc<stat>()
            check(fstat(fd, info.ptr) == 0 && info.st_size == size) { "Download part length mismatch" }
        }
        val finalPath = finalPathFor(head.copyOf(headLength))
        // link publishes the complete inode atomically and never replaces an existing target.
        check(link(partPath, finalPath) == 0) { "Cannot publish download: errno=$errno" }
        published = true
        val finalParent = finalPath.substringBeforeLast('/', ".").ifEmpty { "/" }
        val directory = open(finalParent, O_RDONLY or O_DIRECTORY)
        check(directory >= 0) { "Cannot open download directory: errno=$errno" }
        try {
            check(fsync(directory) == 0) { "Cannot sync download directory: errno=$errno" }
        } finally {
            close(directory)
        }
        check(unlink(partPath) == 0) { "Cannot remove published download part: errno=$errno" }
        return finalPath to size
    } catch (failure: Throwable) {
        // If publication durability is uncertain, retain the source as a recovery copy.
        if (!published) unlink(partPath)
        throw failure
    } finally {
        close(fd)
    }
}

actual fun sweepStaleDownloadParts(dirPath: String, olderThanEpochMs: Long): List<String> {
    val names = NSFileManager.defaultManager.contentsOfDirectoryAtPath(dirPath, null).orEmpty()
    return names.filterIsInstance<String>().filter { name ->
        if (!downloadPartName.matches(name)) return@filter false
        val path = "$dirPath/$name"
        val fd = open(path, O_RDONLY or O_NOFOLLOW or O_NONBLOCK)
        if (fd < 0) return@filter false
        try {
            if (flock(fd, LOCK_EX or LOCK_NB) != 0) return@filter false
            memScoped {
                val info = alloc<stat>()
                val current = alloc<stat>()
                if (fstat(fd, info.ptr) != 0 || (info.st_mode.toInt() and S_IFMT.toInt()) != S_IFREG.toInt()) {
                    return@memScoped false
                }
                val modifiedMs = info.st_mtimespec.tv_sec * 1000L + info.st_mtimespec.tv_nsec / 1_000_000L
                modifiedMs < olderThanEpochMs && lstat(path, current.ptr) == 0 &&
                    current.st_dev == info.st_dev && current.st_ino == info.st_ino && unlink(path) == 0
            }
        } finally {
            close(fd)
        }
    }
}
