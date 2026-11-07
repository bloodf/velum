@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.velum.app.platform

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.timeIntervalSince1970
import platform.posix.*

private fun partialTransfersDir(): String =
    (NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String) + "/downloads"

actual fun openPartialTransfer(sessionId: Long, deviceSerial: String?): PartialTransfer? =
    IosPartialTransferFileStore.open(partialTransfersDir(), sessionId, deviceSerial)

actual fun deletePartialTransfer(sessionId: Long, deviceSerial: String?): Boolean =
    IosPartialTransferFileStore.delete(partialTransfersDir(), sessionId, deviceSerial)

actual fun sweepStalePartialTransfers(nowEpochMs: Long): Int =
    IosPartialTransferFileStore.sweepStale(partialTransfersDir(), nowEpochMs)

actual fun deleteAllPartialTransfers() = IosPartialTransferFileStore.deleteAll(partialTransfersDir())

/** The checkpoint is replaced only after the data has reached disk. Uncheckpointed tails are discarded. */
internal class IosPartialTransferFileStore private constructor(
    private val path: String,
    private val checkpointBytes: Int,
) : PartialTransfer {
    private val file = fopen("$path.partial", "r+b") ?: fopen("$path.partial", "w+b") ?: error("Cannot open partial download")
    private var pending = 0
    private var closed = false

    init {
        try {
            check(fseek(file, 0, SEEK_END) == 0)
            val size = ftell(file)
            check(size >= 0)
            val durable = readBytes("$path.offset")?.decodeToString()?.trim()?.toLongOrNull()
            val length = durable?.takeIf { it in 0..size } ?: 0L
            check(ftruncate(fileno(file), length) == 0)
            check(fseek(file, length, SEEK_SET) == 0)
            if (length == 0L) clearVerdicts()
        } catch (failure: Throwable) {
            fclose(file)
            throw failure
        }
    }

    override fun write(data: ByteArray) {
        check(!closed)
        if (data.isEmpty()) return
        data.usePinned { check(fwrite(it.addressOf(0), 1u, data.size.toULong(), file) == data.size.toULong()) }
        pending += data.size
        if (pending >= checkpointBytes) flush()
    }

    override fun length(): Long {
        flush()
        check(fseek(file, 0, SEEK_END) == 0)
        return ftell(file).also { check(it >= 0) }
    }

    override fun truncate(length: Long) {
        check(!closed)
        require(length >= 0)
        check(fflush(file) == 0)
        check(ftruncate(fileno(file), length) == 0)
        check(fseek(file, length, SEEK_SET) == 0)
        if (length == 0L) clearVerdicts()
        pending = 1
        flush()
    }

    override fun read(offset: Long, length: Int): ByteArray {
        require(offset >= 0 && length >= 0)
        flush()
        check(fseek(file, offset, SEEK_SET) == 0)
        try {
            return ByteArray(length).also { bytes ->
                if (bytes.isNotEmpty()) bytes.usePinned {
                    check(fread(it.addressOf(0), 1u, length.toULong(), file) == length.toULong()) { "Cannot read partial download" }
                }
            }
        } finally {
            check(fseek(file, 0, SEEK_END) == 0)
        }
    }

    private fun flush() {
        check(!closed)
        if (pending == 0) return
        check(fflush(file) == 0)
        check(fsync(fileno(file)) == 0)
        atomicWrite("$path.offset", ftell(file).toString().encodeToByteArray())
        pending = 0
    }

    private fun clearVerdicts() {
        listOf(".offset", ".crcmismatch", ".readfail").forEach { removeIfPresent(path + it) }
    }

    override fun crcMismatch(): Boolean = exists("$path.crcmismatch")
    override fun setCrcMismatch(mismatch: Boolean) {
        if (mismatch) atomicWrite("$path.crcmismatch", byteArrayOf(49)) else removeIfPresent("$path.crcmismatch")
    }
    override fun readFailures(): Int = readBytes("$path.readfail")?.decodeToString()?.trim()?.toIntOrNull()?.coerceAtLeast(0) ?: 0
    override fun setReadFailures(count: Int) {
        if (count > 0) atomicWrite("$path.readfail", count.toString().encodeToByteArray()) else removeIfPresent("$path.readfail")
    }
    override fun close() {
        if (!closed) {
            closed = true
            check(fclose(file) == 0)
        }
    }
    override fun delete() {
        close()
        suffixes.forEach { removeIfPresent(path + it) }
    }

    companion object {
        private val files get() = NSFileManager.defaultManager
        private val suffixes = listOf(".partial", ".offset", ".crcmismatch", ".readfail", ".offset.tmp", ".crcmismatch.tmp", ".readfail.tmp")
        private fun exists(path: String) = files.fileExistsAtPath(path)
        private fun removeIfPresent(path: String) {
            if (exists(path)) check(files.removeItemAtPath(path, null)) { "Cannot delete partial download" }
        }
        private fun readBytes(path: String): ByteArray? {
            if (!exists(path)) return null
            val input = fopen(path, "rb") ?: error("Cannot read partial metadata")
            try {
                check(fseek(input, 0, SEEK_END) == 0)
                val size = ftell(input)
                check(size in 0..Int.MAX_VALUE.toLong())
                rewind(input)
                return ByteArray(size.toInt()).also { bytes ->
                    if (bytes.isNotEmpty()) bytes.usePinned {
                        check(fread(it.addressOf(0), 1u, size.toULong(), input) == size.toULong())
                    }
                }
            } finally { fclose(input) }
        }
        private fun atomicWrite(path: String, bytes: ByteArray) {
            val output = fopen("$path.tmp", "wb") ?: error("Cannot write partial metadata")
            try {
                if (bytes.isNotEmpty()) bytes.usePinned {
                    check(fwrite(it.addressOf(0), 1u, bytes.size.toULong(), output) == bytes.size.toULong())
                }
                check(fflush(output) == 0)
                check(fsync(fileno(output)) == 0)
            } finally { fclose(output) }
            check(rename("$path.tmp", path) == 0) { "Cannot replace partial metadata" }
        }
        private fun serialHash(salt: ByteArray, serial: String) = sha256Bytes(salt + serial.encodeToByteArray()).take(32)
        private fun serialKey(dir: String, serial: String?): String {
            if (serial.isNullOrBlank()) return "noserial"
            val saltPath = "$dir/serial-key.salt"
            val salt = readBytes(saltPath)?.takeIf { it.size == 16 } ?: ByteArray(16).also { bytes ->
                bytes.usePinned { arc4random_buf(it.addressOf(0), 16u) }
                atomicWrite(saltPath, bytes)
            }
            return serialHash(salt, serial)
        }
        fun open(dir: String, sessionId: Long, deviceSerial: String?, checkpointBytes: Int = 256 * 1024): IosPartialTransferFileStore {
            check(files.createDirectoryAtPath(dir, true, null, null)) { "Cannot create partial download directory" }
            val store = IosPartialTransferFileStore("$dir/$sessionId-${serialKey(dir, deviceSerial)}", checkpointBytes)
            if (deviceSerial.isNullOrBlank()) store.truncate(0)
            return store
        }
        fun delete(dir: String, sessionId: Long, deviceSerial: String?): Boolean {
            val serialPart = if (deviceSerial.isNullOrBlank()) "noserial" else {
                val salt = readBytes("$dir/serial-key.salt")?.takeIf { it.size == 16 }
                if (salt == null) {
                    if (!exists(dir)) return true
                    val names = files.contentsOfDirectoryAtPath(dir, null) ?: return false
                    val matches = names.filterIsInstance<String>().filter { it.startsWith("$sessionId-") }
                    matches.forEach { files.removeItemAtPath("$dir/$it", null) }
                    return matches.none { it.endsWith(".partial") && exists("$dir/$it") }
                }
                serialHash(salt, deviceSerial)
            }
            val path = "$dir/$sessionId-$serialPart"
            suffixes.forEach { files.removeItemAtPath(path + it, null) }
            return !exists("$path.partial")
        }
        fun deleteAll(dir: String) {
            if (exists(dir)) files.removeItemAtPath(dir, null)
            check(!exists(dir)) { "Could not delete partial downloads" }
        }
        fun sweepStale(dir: String, nowEpochMs: Long): Int =
            files.contentsOfDirectoryAtPath(dir, null).orEmpty().filterIsInstance<String>().count { name ->
                val path = "$dir/$name"
                val modified = (files.attributesOfItemAtPath(path, null)?.get(NSFileModificationDate) as? NSDate)
                    ?.timeIntervalSince1970?.times(1000)?.toLong() ?: 0L
                suffixes.any { name.endsWith(it) } && modified > 0 && nowEpochMs - modified >= STALE_PARTIAL_TRANSFER_MS &&
                    files.removeItemAtPath(path, null)
            }
    }
}
