@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.velum.core.templates

import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSBundle
import platform.zlib.gzclose
import platform.zlib.gzerror
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.gzopen
import platform.zlib.gzread

/** Reads the checked-in catalogs copied by core into the application resources. */
internal object BundledCatalogAsset {
    private const val DIRECTORY = "velum-core-catalog"

    fun readJsonl(name: String): String = buildString {
        forEachLine(name, filter = false) { append(it) }
    }

    fun forEachLine(name: String, filter: Boolean = true, action: (String) -> Unit) {
        val path = NSBundle.mainBundle.pathForResource(name, ofType = null, inDirectory = DIRECTORY)
            ?: error("Missing bundled catalog: $DIRECTORY/$name")
        forEachLineAtPath(path, filter, action)
    }

    internal fun forEachLineAtPath(path: String, filter: Boolean = true, action: (String) -> Unit) {
        // zlib transparently reads both gzip and uncompressed files.
        val stream = gzopen(path, "rb") ?: error("Cannot open catalog: $path")
        val buffer = ByteArray(16 * 1024)
        var pending = ByteArray(0)
        try {
            buffer.usePinned { pinned ->
                while (true) {
                    val count = gzread(stream, pinned.addressOf(0), buffer.size.toUInt())
                    check(count >= 0) { "Cannot decompress catalog: $path" }
                    if (count == 0) {
                        memScoped {
                            val status = alloc<IntVar>()
                            gzerror(stream, status.ptr)
                            check(status.value == Z_OK || status.value == Z_STREAM_END) {
                                "Incomplete gzip catalog: $path (zlib ${status.value})"
                            }
                        }
                        break
                    }
                    val bytes = pending + buffer.copyOf(count)
                    var start = 0
                    for (index in bytes.indices) {
                        if (bytes[index] == 10.toByte()) {
                            emit(bytes.copyOfRange(start, index + 1).decodeToString(throwOnInvalidSequence = true), filter, action)
                            start = index + 1
                        }
                    }
                    pending = bytes.copyOfRange(start, bytes.size)
                    check(pending.size <= 4 * 1024 * 1024) { "Catalog line exceeds 4 MiB: $path" }
                }
            }
            if (pending.isNotEmpty()) emit(pending.decodeToString(throwOnInvalidSequence = true), filter, action)
        } finally {
            gzclose(stream)
        }
    }

    private fun emit(line: String, filter: Boolean, action: (String) -> Unit) {
        if (!filter) {
            action(line)
        } else {
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) action(trimmed)
        }
    }
}
