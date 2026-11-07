@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.velum.ble.client

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.Foundation.NSTemporaryDirectory
import platform.posix.EINTR
import platform.posix.close
import platform.posix.errno
import platform.posix.mkstemp
import platform.posix.pread
import platform.posix.unlink
import platform.posix.write

actual fun createTempSessionFile(): TempSessionFile? {
    val template = (NSTemporaryDirectory() + "velum-ble-session-XXXXXX\u0000").encodeToByteArray()
    return template.usePinned { pinned ->
        val fd = mkstemp(pinned.addressOf(0))
        if (fd < 0) null else IosTempSessionFile(fd, pinned.addressOf(0).toKString())
    }
}

internal class IosTempSessionFile(
    private val fd: Int,
    private val path: String,
    private val maxBytes: Long = PlaudDeviceClient.MAX_SESSION_DOWNLOAD_BYTES,
) : TempSessionFile {
    private var written = 0L
    private var closed = false
    private var deleted = false

    override fun write(data: ByteArray) {
        check(!closed) { "Temporary session file is closed" }
        require(data.size.toLong() <= maxBytes - written) { "Session download exceeded $maxBytes-byte cap" }
        if (data.isEmpty()) return
        try {
            data.usePinned { pinned ->
                var offset = 0
                while (offset < data.size) {
                    val count = write(fd, pinned.addressOf(offset), (data.size - offset).toULong())
                    if (count < 0 && errno == EINTR) continue
                    check(count > 0) { "Temporary session file write failed: errno=$errno" }
                    offset += count.toInt()
                    written += count
                }
            }
        } catch (failure: Throwable) {
            delete()
            throw failure
        }
    }

    override fun size(): Long = written

    override fun readAll(): ByteArray {
        check(!closed) { "Temporary session file is closed" }
        check(written <= Int.MAX_VALUE) { "Temporary session file is too large to read" }
        val bytes = ByteArray(written.toInt())
        if (bytes.isEmpty()) return bytes
        bytes.usePinned { pinned ->
            var offset = 0
            while (offset < bytes.size) {
                val count = pread(fd, pinned.addressOf(offset), (bytes.size - offset).toULong(), offset.toLong())
                if (count < 0 && errno == EINTR) continue
                check(count > 0) { "Temporary session file read failed at $offset: errno=$errno" }
                offset += count.toInt()
            }
        }
        return bytes
    }

    override fun absolutePath(): String = path

    override fun delete() {
        if (!closed) {
            closed = true
            close(fd)
        }
        if (!deleted) deleted = unlink(path) == 0
    }
}
