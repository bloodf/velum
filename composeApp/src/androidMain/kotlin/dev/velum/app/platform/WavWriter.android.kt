package dev.velum.app.platform

import java.io.File
import java.io.RandomAccessFile

internal actual fun openWavFile(absolutePath: String): WavFileHandle =
    JvmWavFileHandle(File(absolutePath))

internal class JvmWavFileHandle(private val file: File) : WavFileHandle {
    init {
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { it.setLength(0) }
    }

    override fun writeWavHeader(dataSize: Long, sampleRateHz: Int) {
        RandomAccessFile(file, "rw").use { raf ->
            val channels = 1
            val bitsPerSample = 16
            val byteRate = sampleRateHz * channels * bitsPerSample / 8
            val blockAlign = channels * bitsPerSample / 8
            val chunkSize = 36L + dataSize
            raf.seek(0)
            raf.writeBytes("RIFF")
            writeIntLE(raf, chunkSize.toInt())
            raf.writeBytes("WAVE")
            raf.writeBytes("fmt ")
            writeIntLE(raf, 16)
            writeShortLE(raf, 1)
            writeShortLE(raf, channels.toShort())
            writeIntLE(raf, sampleRateHz)
            writeIntLE(raf, byteRate)
            writeShortLE(raf, blockAlign.toShort())
            writeShortLE(raf, bitsPerSample.toShort())
            raf.writeBytes("data")
            writeIntLE(raf, dataSize.toInt())
            raf.fd.sync()
        }
    }

    override fun append(bytes: ByteArray) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(raf.length().coerceAtLeast(44))
            raf.write(bytes)
        }
    }

    override fun close() = Unit

    override fun length(): Long = file.length()

    private fun writeIntLE(raf: RandomAccessFile, value: Int) {
        raf.write(value and 0xFF)
        raf.write((value shr 8) and 0xFF)
        raf.write((value shr 16) and 0xFF)
        raf.write((value shr 24) and 0xFF)
    }

    private fun writeShortLE(raf: RandomAccessFile, value: Short) {
        val v = value.toInt()
        raf.write(v and 0xFF)
        raf.write((v shr 8) and 0xFF)
    }
}
