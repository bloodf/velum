package dev.velum.app.platform

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom

/** Beside, not inside, the recordings directory: the recording sweeps never see these files. */
private fun partialTransfersDir() = File(File(recordingsDirectoryPath()).parentFile, "downloads")

actual fun openPartialTransfer(sessionId: Long, deviceSerial: String?): PartialTransfer? =
    PartialTransferFileStore.open(partialTransfersDir(), sessionId, deviceSerial)

actual fun deletePartialTransfer(sessionId: Long, deviceSerial: String?): Boolean =
    PartialTransferFileStore.delete(partialTransfersDir(), sessionId, deviceSerial)

actual fun sweepStalePartialTransfers(nowEpochMs: Long): Int =
    PartialTransferFileStore.sweepStale(partialTransfersDir(), nowEpochMs)

actual fun deleteAllPartialTransfers() = PartialTransferFileStore.deleteAll(partialTransfersDir())

/**
 * `<key>.partial` holds the bytes and `<key>.offset` the last durable length, replaced atomically after
 * an fsync. On open the file is cut back to that length: bytes past it may be torn or missing after a
 * crash. A missing or implausible checkpoint restarts from 0, which only costs a re-download.
 */
internal class PartialTransferFileStore private constructor(
    private val data: File,
    private val checkpoint: File,
    private val checkpointBytes: Int,
) : PartialTransfer {
    private val mismatchMark = File(data.path.removeSuffix(DATA_SUFFIX) + CRC_MISMATCH_SUFFIX)
    private val readFailMark = File(data.path.removeSuffix(DATA_SUFFIX) + READ_FAIL_SUFFIX)

    private val file = RandomAccessFile(data, "rw")
    private var pending = 0

    init {
        val durable = checkpoint.takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()
        val length = durable?.takeIf { it in 0..file.length() } ?: 0L
        if (length == 0L) { checkpoint.delete(); mismatchMark.delete(); readFailMark.delete() } // data restarts from zero: old verdicts no longer apply
        file.setLength(length)
        file.seek(length)
    }

    override fun write(data: ByteArray) {
        file.write(data)
        pending += data.size
        if (pending >= checkpointBytes) flush()
    }

    /** Flushes first, so a resume point is always durable. */
    override fun length(): Long {
        flush()
        return file.length()
    }

    override fun truncate(length: Long) {
        if (length == 0L) { mismatchMark.delete(); readFailMark.delete() } // restarting from zero: old verdicts no longer apply
        file.setLength(length)
        file.seek(length)
        pending = 1
        flush()
    }

    override fun read(offset: Long, length: Int): ByteArray {
        flush()
        val out = ByteArray(length)
        file.seek(offset)
        file.readFully(out)
        file.seek(file.length())
        return out
    }

    private fun flush() {
        if (pending == 0) return
        file.fd.sync()
        val temp = File(checkpoint.path + ".tmp")
        temp.writeText(file.length().toString())
        try {
            Files.move(temp.toPath(), checkpoint.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), checkpoint.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        pending = 0
    }

    override fun readFailures(): Int = readFailMark.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()?.coerceAtLeast(0) ?: 0

    override fun setReadFailures(count: Int) {
        if (count > 0) readFailMark.writeText(count.toString()) else readFailMark.delete()
    }

    override fun crcMismatch(): Boolean = mismatchMark.isFile

    override fun setCrcMismatch(mismatch: Boolean) {
        if (mismatch) mismatchMark.writeText("1") else mismatchMark.delete()
    }

    override fun close() = file.close()

    override fun delete() {
        file.close()
        data.delete()
        checkpoint.delete()
        mismatchMark.delete()
        readFailMark.delete()
    }

    companion object {
        /** About 8 seconds of BLE transfer at 32 KB/s. */
        private const val DEFAULT_CHECKPOINT_BYTES = 256 * 1024
        private const val DATA_SUFFIX = ".partial"
        private const val CHECKPOINT_SUFFIX = ".offset"
        private const val CRC_MISMATCH_SUFFIX = ".crcmismatch"
        private const val READ_FAIL_SUFFIX = ".readfail"
        private const val SALT_FILE = "serial-key.salt"
        private const val SALT_BYTES = 16
        private const val NO_SERIAL = "noserial"

        fun open(
            dir: File,
            sessionId: Long,
            deviceSerial: String?,
            checkpointBytes: Int = DEFAULT_CHECKPOINT_BYTES,
        ): PartialTransferFileStore {
            dir.mkdirs()
            val key = keyOf(sessionId, serialKey(dir, deviceSerial))
            val store = PartialTransferFileStore(File(dir, key + DATA_SUFFIX), File(dir, key + CHECKPOINT_SUFFIX), checkpointBytes)
            if (deviceSerial.isNullOrBlank()) store.truncate(0)
            return store
        }

        /**
         * Removes the session's files by name, without opening or creating anything, so it works on a full disk.
         * Only the data file decides the result: it holds the audio, and a writer still unwinding can leave a late
         * `.offset`, which the next open ignores. With no salt, no partial with a serial can exist.
         */
        fun delete(dir: File, sessionId: Long, deviceSerial: String?): Boolean {
            val serialPart = if (deviceSerial.isNullOrBlank()) {
                NO_SERIAL
            } else {
                val salt = File(dir, SALT_FILE).takeIf { it.isFile }?.readBytes()?.takeIf { it.size == SALT_BYTES } ?: return true
                serialHash(salt, deviceSerial)
            }
            val key = keyOf(sessionId, serialPart)
            listOf(CHECKPOINT_SUFFIX, CRC_MISMATCH_SUFFIX, READ_FAIL_SUFFIX).forEach { File(dir, key + it).delete() }
            val data = File(dir, key + DATA_SUFFIX)
            data.delete()
            return !data.exists()
        }

        private fun keyOf(sessionId: Long, serialPart: String) = "$sessionId-$serialPart"

        /**
         * A file name must not reveal the serial. Serials are short, so a bare hash could be reversed by
         * trying them all; a random per-install salt stops that for anyone holding only the file names.
         * The salt sits beside the files, so it does not help against someone holding this directory.
         */
        private fun serialKey(dir: File, deviceSerial: String?): String {
            if (deviceSerial.isNullOrBlank()) return NO_SERIAL
            return serialHash(installSalt(dir), deviceSerial)
        }

        private fun serialHash(salt: ByteArray, deviceSerial: String): String {
            val digest = MessageDigest.getInstance("SHA-256").apply {
                update(salt)
                update(deviceSerial.encodeToByteArray())
            }.digest()
            return digest.take(16).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        }

        private fun installSalt(dir: File): ByteArray {
            val file = File(dir, SALT_FILE)
            if (file.isFile) file.readBytes().takeIf { it.size == SALT_BYTES }?.let { return it }
            val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
            val temp = File(dir, "$SALT_FILE.tmp")
            temp.writeBytes(salt)
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            return salt
        }

        fun deleteAll(dir: File) {
            check(dir.deleteRecursively() || !dir.exists()) { "Could not delete partial downloads" }
        }

        fun sweepStale(dir: File, nowEpochMs: Long): Int =
            dir.listFiles().orEmpty().count { file ->
                val modified = file.lastModified()
                file.isFile &&
                    (file.name.endsWith(DATA_SUFFIX) || file.name.endsWith(CRC_MISMATCH_SUFFIX) || file.name.endsWith(READ_FAIL_SUFFIX) || file.name.endsWith(CHECKPOINT_SUFFIX) || file.name.endsWith("$CHECKPOINT_SUFFIX.tmp")) &&
                    modified > 0L && nowEpochMs - modified >= STALE_PARTIAL_TRANSFER_MS &&
                    file.delete()
            }
    }
}
