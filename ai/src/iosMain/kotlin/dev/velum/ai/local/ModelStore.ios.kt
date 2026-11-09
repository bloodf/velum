package dev.velum.ai.local

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSNumber
import platform.Foundation.dataWithBytes
import platform.Foundation.writeToFile
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class)
actual class ModelStoreDir actual constructor(absolutePath: String) {
    actual val absolutePath: String = absolutePath.trimEnd('/')

    actual val name: String = absolutePath.substringAfterLast('/')

    actual fun resolve(child: String): ModelStoreDir =
        ModelStoreDir("$absolutePath/${child.trim('/')}")

    actual fun exists(): Boolean =
        NSFileManager.defaultManager.fileExistsAtPath(absolutePath)

    actual fun freeSpaceBytes(): Long? {
        val fm = NSFileManager.defaultManager
        var path = absolutePath
        while (path.isNotEmpty() && !fm.fileExistsAtPath(path)) path = path.substringBeforeLast('/', "")
        if (path.isEmpty()) path = "/"
        val attrs = fm.attributesOfFileSystemForPath(path, error = null) ?: return null
        return (attrs["NSFileSystemFreeSize"] as? NSNumber)?.longLongValue
    }

    actual fun isDirectory(): Boolean {
        val fm = NSFileManager.defaultManager
        if (!fm.fileExistsAtPath(absolutePath)) return false
        val attrs = fm.attributesOfItemAtPath(absolutePath, error = null) ?: return false
        val type = attrs["NSFileType"] as? String
        return type == "NSFileTypeDirectory"
    }

    actual fun createDirectories() {
        NSFileManager.defaultManager.createDirectoryAtPath(
            absolutePath,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
    }

    actual fun writeBytes(bytes: ByteArray) {
        val parentPath = absolutePath.substringBeforeLast('/', missingDelimiterValue = "")
        if (parentPath.isNotEmpty()) {
            NSFileManager.defaultManager.createDirectoryAtPath(
                parentPath,
                withIntermediateDirectories = true,
                attributes = null,
                error = null,
            )
        }
        bytes.usePinned { pinned ->
            val data = NSData.dataWithBytes(if (bytes.isEmpty()) null else pinned.addressOf(0), bytes.size.toULong())
            check(data.writeToFile(absolutePath, atomically = true)) { "Unable to write a file." }
        }
    }

    actual fun writeBytesDurably(bytes: ByteArray) {
        val parentPath = absolutePath.substringBeforeLast('/', missingDelimiterValue = "")
        if (parentPath.isNotEmpty()) {
            NSFileManager.defaultManager.createDirectoryAtPath(parentPath, withIntermediateDirectories = true, attributes = null, error = null)
        }
        val fd = platform.posix.open(absolutePath, platform.posix.O_WRONLY or platform.posix.O_CREAT or platform.posix.O_TRUNC, 0x1A4)
        check(fd >= 0) { "Unable to open a file for writing." }
        try {
            var written = 0
            bytes.usePinned { pinned ->
                while (written < bytes.size) {
                    val n = platform.posix.write(fd, pinned.addressOf(written), (bytes.size - written).toULong())
                    check(n > 0) { "Unable to write a file." }
                    written += n.toInt()
                }
            }
            check(platform.posix.fsync(fd) == 0) { "Unable to flush a file." }
        } finally {
            platform.posix.close(fd)
        }
    }

    actual fun syncDirectory() {
        val fd = platform.posix.open(absolutePath, platform.posix.O_RDONLY, 0)
        if (fd < 0) return
        platform.posix.fsync(fd)
        platform.posix.close(fd)
    }

    actual fun appendBytes(bytes: ByteArray) {
        val parentPath = absolutePath.substringBeforeLast('/', missingDelimiterValue = "")
        if (parentPath.isNotEmpty()) ModelStoreDir(parentPath).createDirectories()
        val fd = platform.posix.open(absolutePath, platform.posix.O_WRONLY or platform.posix.O_CREAT or platform.posix.O_APPEND, 0x1A4)
        check(fd >= 0) { "Unable to open a file for appending." }
        try {
            bytes.usePinned { pinned ->
                var written = 0
                while (written < bytes.size) {
                    val count = platform.posix.write(fd, pinned.addressOf(written), (bytes.size - written).toULong())
                    if (count < 0 && platform.posix.errno == platform.posix.EINTR) continue
                    check(count > 0) { "Unable to append to a file." }
                    written += count.toInt()
                }
            }
        } finally {
            platform.posix.close(fd)
        }
    }

    actual fun readBytes(): ByteArray {
        val data = NSFileManager.defaultManager.contentsAtPath(absolutePath) ?: return ByteArray(0)
        val length = data.length.toInt()
        val out = ByteArray(length)
        if (length > 0) {
            out.usePinned { pinned ->
                memcpy(pinned.addressOf(0), data.bytes, data.length)
            }
        }
        return out
    }

    actual fun readRange(offset: Long, length: Int): ByteArray {
        require(offset >= 0 && length >= 0) { "offset=$offset length=$length" }
        val fd = platform.posix.open(absolutePath, platform.posix.O_RDONLY, 0)
        check(fd >= 0) { "Unable to open a file for reading." }
        try {
            check(platform.posix.lseek(fd, offset, platform.posix.SEEK_SET) == offset) { "Unable to seek in a file." }
            val bytes = ByteArray(length)
            bytes.usePinned { pinned ->
                var read = 0
                while (read < length) {
                    val count = platform.posix.read(fd, pinned.addressOf(read), (length - read).toULong())
                    if (count < 0 && platform.posix.errno == platform.posix.EINTR) continue
                    check(count >= 0) { "Unable to read a file." }
                    require(count > 0) { "File truncated while reading range $offset+$length" }
                    read += count.toInt()
                }
            }
            return bytes
        } finally {
            platform.posix.close(fd)
        }
    }

    actual fun deleteFile(): Boolean = platform.posix.unlink(absolutePath) == 0

    actual fun deleteRecursively() {
        NSFileManager.defaultManager.removeItemAtPath(absolutePath, error = null)
    }

    actual fun listChildren(): List<ModelStoreDir> {
        val contents = NSFileManager.defaultManager.contentsOfDirectoryAtPath(absolutePath, error = null)
            ?: return emptyList()
        return contents.mapNotNull { name ->
            (name as? String)?.let { ModelStoreDir("$absolutePath/$it") }
        }
    }

    actual fun length(): Long {
        val attrs = NSFileManager.defaultManager.attributesOfItemAtPath(absolutePath, error = null)
            ?: return 0L
        val size = attrs["NSFileSize"] as? NSNumber
        return size?.longValue ?: 0L
    }

    actual fun lastModifiedMillis(): Long {
        val attrs = NSFileManager.defaultManager.attributesOfItemAtPath(absolutePath, error = null) ?: return 0L
        val modified = attrs["NSFileModificationDate"] as? NSDate ?: return 0L
        return (modified.timeIntervalSince1970 * 1000.0).toLong()
    }

    actual fun renameTo(dest: ModelStoreDir): Boolean {
        val fm = NSFileManager.defaultManager
        val parent = dest.absolutePath.substringBeforeLast('/', missingDelimiterValue = "")
        if (parent.isNotEmpty()) {
            fm.createDirectoryAtPath(
                parent,
                withIntermediateDirectories = true,
                attributes = null,
                error = null,
            )
        }
        // A directory target is removed first (as documented); a file target is replaced by rename(2),
        // which is atomic, so a failure leaves the old file instead of deleting it first.
        if (isDirectoryAt(dest.absolutePath)) fm.removeItemAtPath(dest.absolutePath, error = null)
        return platform.posix.rename(absolutePath, dest.absolutePath) == 0
    }

    private fun isDirectoryAt(path: String): Boolean = ModelStoreDir(path).isDirectory()
}

actual fun defaultModelStoreRoot(): ModelStoreDir {
    return ModelStoreDir("${NSHomeDirectory()}/Documents/velum/models")
}
