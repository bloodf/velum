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

actual fun sweepStalePartialTransfers(nowEpochMs: Long): Int =
    PartialTransferFileStore.sweepStale(partialTransfersDir(), nowEpochMs)

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
    private val file = RandomAccessFile(data, "rw")
    private var pending = 0

    init {
        val durable = checkpoint.takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()
        val length = durable?.takeIf { it in 0..file.length() } ?: 0L
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

    override fun close() = file.close()

    override fun delete() {
        file.close()
        data.delete()
        checkpoint.delete()
    }

    companion object {
        /** About 8 seconds of BLE transfer at 32 KB/s. */
        private const val DEFAULT_CHECKPOINT_BYTES = 256 * 1024
        private const val DATA_SUFFIX = ".partial"
        private const val CHECKPOINT_SUFFIX = ".offset"
        private const val SALT_FILE = "serial-key.salt"
        private const val SALT_BYTES = 16

        fun open(
            dir: File,
            sessionId: Long,
            deviceSerial: String?,
            checkpointBytes: Int = DEFAULT_CHECKPOINT_BYTES,
        ): PartialTransferFileStore {
            dir.mkdirs()
            val key = "$sessionId-${serialKey(dir, deviceSerial)}"
            val store = PartialTransferFileStore(File(dir, key + DATA_SUFFIX), File(dir, key + CHECKPOINT_SUFFIX), checkpointBytes)
            if (deviceSerial.isNullOrBlank()) store.truncate(0)
            return store
        }

        /**
         * A file name must not reveal the serial. Serials are short, so a bare hash could be reversed by
         * trying them all; a random per-install salt stops that for anyone holding only the file names.
         * The salt sits beside the files, so it does not help against someone holding this directory.
         */
        private fun serialKey(dir: File, deviceSerial: String?): String {
            if (deviceSerial.isNullOrBlank()) return "noserial"
            val digest = MessageDigest.getInstance("SHA-256").apply {
                update(installSalt(dir))
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

        fun sweepStale(dir: File, nowEpochMs: Long): Int =
            dir.listFiles().orEmpty().count { file ->
                val modified = file.lastModified()
                file.isFile &&
                    (file.name.endsWith(DATA_SUFFIX) || file.name.endsWith(CHECKPOINT_SUFFIX)) &&
                    modified > 0L && nowEpochMs - modified >= STALE_PARTIAL_TRANSFER_MS &&
                    file.delete()
            }
    }
}
