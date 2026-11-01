package dev.velum.app.platform

import java.io.File
import java.io.RandomAccessFile

internal actual fun openWavFile(absolutePath: String): WavFileHandle =
    JvmWavFileHandle(File(absolutePath))

internal actual fun recoverOrFinalizeWav(
    absolutePath: String,
    sampleRateHz: Int,
): WavFinish? = jvmRecoverOrFinalizeWav(absolutePath, sampleRateHz)

internal class JvmWavFileHandle(private val file: File) : WavFileHandle {
    init {
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { it.setLength(0) }
    }

    override fun writeWavHeader(dataSize: Long, sampleRateHz: Int) {
        RandomAccessFile(file, "rw").use { raf ->
            writeWavHeaderTo(raf, dataSize, sampleRateHz)
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
}

internal fun jvmRecoverOrFinalizeWav(
    absolutePath: String,
    sampleRateHz: Int,
): WavFinish? {
    return recoverDetailedWav(absolutePath, sampleRateHz).finishedOrNull()
}

internal fun writeWavHeaderTo(
    raf: RandomAccessFile,
    dataSize: Long,
    sampleRateHz: Int,
) {
    raf.seek(0)
    raf.write(pcm16MonoWavHeader(dataSize, sampleRateHz))
}
