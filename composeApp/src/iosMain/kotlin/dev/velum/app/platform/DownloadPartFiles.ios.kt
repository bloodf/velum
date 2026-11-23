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
    var open = true
    var ownedDevice = 0L
    var ownedInode = 0UL
    var headLength = 0
    var size = 0L
    try {
        memScoped {
            val info = alloc<stat>()
            check(fstat(fd, info.ptr) == 0) { "Cannot inspect download part" }
            ownedDevice = info.st_dev.toLong()
            ownedInode = info.st_ino.toULong()
        }
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
        require(finalPath != partPath) { "Download target must differ from part" }
        val result = finalPath to size
        // Close errors are pre-commit failures. Never retry close: the fd may already be released.
        open = false
        check(close(fd) == 0) { "Cannot close download part: errno=$errno" }
        memScoped {
            val current = alloc<stat>()
            check(lstat(partPath, current.ptr) == 0 && current.st_dev.toLong() == ownedDevice &&
                current.st_ino.toULong() == ownedInode) { "Download part ownership changed" }
        }
        publishDownloadPart(partPath, finalPath)
        return result
    } catch (failure: Throwable) {
        memScoped {
            val current = alloc<stat>()
            if (lstat(partPath, current.ptr) == 0 && current.st_dev.toLong() == ownedDevice &&
                current.st_ino.toULong() == ownedInode) unlink(partPath)
        }
        throw failure
    } finally {
        if (open) close(fd)
    }
}

/** Directory sync is best effort after commit, as on JVM; publication must reach caller cleanup. */
internal fun publishDownloadPart(
    partPath: String,
    finalPath: String,
    syncDirectory: (String) -> Unit = ::syncDownloadDirectory,
) {
    memScoped {
        val source = alloc<stat>()
        check(lstat(partPath, source.ptr) == 0 &&
            (source.st_mode.toInt() and S_IFMT.toInt()) == S_IFREG.toInt()) { "Invalid download part" }
        val target = alloc<stat>()
        if (lstat(finalPath, target.ptr) == 0) {
            check(source.st_dev != target.st_dev || source.st_ino != target.st_ino) { "Download target aliases part" }
            check((target.st_mode.toInt() and S_IFMT.toInt()) == S_IFREG.toInt()) {
                "Download target is not a regular file"
            }
        } else {
            check(errno == ENOENT) { "Cannot inspect download target: errno=$errno" }
        }
    }
    check(rename(partPath, finalPath) == 0) { "Cannot publish download: errno=$errno" }
    // No rollback or part deletion here: another writer may already have replaced the target.
    runCatching {
        syncDirectory(finalPath.substringBeforeLast('/', ".").ifEmpty { "/" })
    }
}

private fun syncDownloadDirectory(parent: String) {
    val directory = open(parent, O_RDONLY or O_DIRECTORY)
    check(directory >= 0) { "Cannot open download directory: errno=$errno" }
    try {
        check(fsync(directory) == 0) { "Cannot sync download directory: errno=$errno" }
    } finally {
        close(directory)
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
