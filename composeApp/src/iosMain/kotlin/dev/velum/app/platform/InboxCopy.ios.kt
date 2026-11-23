@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.velum.app.platform

import kotlinx.cinterop.*
import platform.posix.*

/** Exclusive output, no links, regular input, and at most 64 KiB resident audio. */
internal fun copyInboxFile(source: String, destination: String, limit: Long = 1L shl 30) {
    val input = open(source, O_RDONLY or O_NOFOLLOW or O_NONBLOCK)
    check(input >= 0) { "Cannot open Inbox file" }
    try {
        memScoped {
            val info = alloc<stat>()
            check(fstat(input, info.ptr) == 0 && (info.st_mode.toInt() and S_IFMT.toInt()) == S_IFREG.toInt())
            check(info.st_size in 1..limit) { "Inbox file size rejected" }
        }
        val output = open(destination, O_WRONLY or O_CREAT or O_EXCL or O_NOFOLLOW, 384)
        check(output >= 0) { "Cannot create Inbox copy" }
        var complete = false
        try {
            var total = 0L
            val buffer = ByteArray(64 * 1024)
            buffer.usePinned { bytes ->
                while (true) {
                    val count = read(input, bytes.addressOf(0), buffer.size.toULong())
                    if (count < 0 && errno == EINTR) continue
                    check(count >= 0) { "Cannot read Inbox file" }
                    if (count == 0L) break
                    total += count
                    check(total <= limit) { "Inbox file exceeds size limit" }
                    var offset = 0
                    while (offset < count) {
                        val written = write(output, bytes.addressOf(offset), (count - offset).toULong())
                        if (written < 0 && errno == EINTR) continue
                        check(written > 0) { "Cannot write Inbox copy" }
                        offset += written.toInt()
                    }
                }
            }
            check(total > 0 && fsync(output) == 0) { "Cannot persist Inbox copy" }
            complete = true
        } finally {
            close(output)
            if (!complete) unlink(destination)
        }
    } finally { close(input) }
}
