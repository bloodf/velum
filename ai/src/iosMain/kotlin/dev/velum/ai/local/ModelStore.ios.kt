package dev.velum.ai.local

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSNumber
import platform.Foundation.closeFile
import platform.Foundation.dataWithBytes
import platform.Foundation.fileHandleForReadingAtPath
import platform.Foundation.readDataOfLength
import platform.Foundation.seekToFileOffset
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
            val data = NSData.dataWithBytes(pinned.addressOf(0), bytes.size.toULong())
            data.writeToFile(absolutePath, atomically = true)
        }
    }

    actual fun appendBytes(bytes: ByteArray) {
        val existing = if (exists()) readBytes() else ByteArray(0)
        writeBytes(existing + bytes)
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
        if (length == 0) return ByteArray(0)
        val fileLen = length()
        require(offset + length <= fileLen) { "range $offset+$length exceeds $fileLen" }
        val handle = NSFileHandle.fileHandleForReadingAtPath(absolutePath)
            ?: return ByteArray(0)
        try {
            handle.seekToFileOffset(offset.toULong())
            val data = handle.readDataOfLength(length.toULong())
            val outLen = data.length.toInt()
            val out = ByteArray(outLen)
            if (outLen > 0) {
                out.usePinned { pinned ->
                    memcpy(pinned.addressOf(0), data.bytes, data.length)
                }
            }
            return out
        } finally {
            handle.closeFile()
        }
    }

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

    actual fun renameTo(dest: ModelStoreDir): Boolean {
        val fm = NSFileManager.defaultManager
        if (fm.fileExistsAtPath(dest.absolutePath)) {
            fm.removeItemAtPath(dest.absolutePath, error = null)
        }
        val parent = dest.absolutePath.substringBeforeLast('/', missingDelimiterValue = "")
        if (parent.isNotEmpty()) {
            fm.createDirectoryAtPath(
                parent,
                withIntermediateDirectories = true,
                attributes = null,
                error = null,
            )
        }
        return fm.moveItemAtPath(absolutePath, toPath = dest.absolutePath, error = null)
    }
}

actual fun defaultModelStoreRoot(): ModelStoreDir {
    return ModelStoreDir("${NSHomeDirectory()}/Documents/velum/models")
}
