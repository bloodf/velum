package dev.velum.ble.client

import java.io.File
import java.io.FileOutputStream

internal actual fun createTempSessionFile(): TempSessionFile? =
    FileTempSessionFile(File.createTempFile("velum-ble-session-", ".bin"))

internal class FileTempSessionFile(
    private val file: File,
) : TempSessionFile {
    private val out = FileOutputStream(file)
    private var written = 0L

    override fun write(data: ByteArray) {
        if (data.isEmpty()) return
        out.write(data)
        written += data.size.toLong()
    }

    override fun size(): Long = written

    override fun readAll(): ByteArray {
        out.flush()
        out.fd.sync()
        return file.readBytes()
    }

    override fun absolutePath(): String = file.absolutePath

    override fun delete() {
        runCatching { out.close() }
        runCatching { file.delete() }
    }
}
