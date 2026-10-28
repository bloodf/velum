package dev.velum.ble.client

import java.io.File
import java.io.RandomAccessFile

actual fun createSessionPartialFileFactory(directoryPath: String): SessionPartialFileFactory =
    FileSessionPartialFileFactory(File(directoryPath))

class FileSessionPartialFileFactory(
    private val directory: File,
) : SessionPartialFileFactory {
    init {
        directory.mkdirs()
    }

    override fun allocatePath(sessionId: Long): String {
        val file = File(directory, "session-$sessionId-${System.nanoTime()}.partial")
        return file.absolutePath
    }

    override fun open(path: String): SessionPartialFile = FileSessionPartialFile(File(path))
}

class FileSessionPartialFile(
    private val file: File,
) : SessionPartialFile {
    private val raf: RandomAccessFile = RandomAccessFile(file, "rw")
    private var written: Long = file.length()

    init {
        raf.seek(written)
    }

    override fun write(data: ByteArray) {
        if (data.isEmpty()) return
        raf.write(data)
        written += data.size.toLong()
    }

    override fun size(): Long = written

    override fun absolutePath(): String = file.absolutePath

    override fun sync() {
        raf.fd.sync()
    }

    override fun readAt(offset: Long, length: Int): ByteArray {
        require(offset >= 0 && length >= 0)
        require(offset + length <= written) {
            "readAt offset=$offset length=$length exceeds size=$written"
        }
        val pos = raf.filePointer
        try {
            raf.seek(offset)
            val out = ByteArray(length)
            raf.readFully(out)
            return out
        } finally {
            raf.seek(pos)
        }
    }

    override fun truncateTo(size: Long) {
        require(size >= 0) { "truncate size must be >= 0" }
        require(size <= written) { "truncateTo($size) exceeds size=$written" }
        raf.setLength(size)
        written = size
        raf.seek(written)
        raf.fd.sync()
    }

    override fun close() {
        runCatching { raf.close() }
    }

    override fun delete() {
        close()
        runCatching { file.delete() }
    }
}
