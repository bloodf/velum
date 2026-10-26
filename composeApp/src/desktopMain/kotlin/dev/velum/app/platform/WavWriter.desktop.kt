package dev.velum.app.platform

import java.io.File
import java.io.RandomAccessFile

internal actual fun openWavFile(absolutePath: String): WavFileHandle =
    DesktopWavFileHandle(File(absolutePath))

internal actual fun recoverOrFinalizeWav(
    absolutePath: String,
    sampleRateHz: Int,
): WavFinish? = desktopRecoverOrFinalizeWav(absolutePath, sampleRateHz)

private class DesktopWavFileHandle(private val file: File) : WavFileHandle {
    init {
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { it.setLength(0) }
    }

    override fun writeWavHeader(dataSize: Long, sampleRateHz: Int) {
        RandomAccessFile(file, "rw").use { raf ->
            writeDesktopWavHeader(raf, dataSize, sampleRateHz)
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

private fun desktopRecoverOrFinalizeWav(
    absolutePath: String,
    sampleRateHz: Int,
): WavFinish? {
    var file = File(absolutePath)
    if (absolutePath.endsWith(".partial", ignoreCase = true)) {
        val finalizedName = absolutePath.removeSuffix(".partial").removeSuffix(".PARTIAL")
        val target = File(finalizedName)
        if (file.exists()) {
            if (target.exists() && target.absolutePath != file.absolutePath) {
                target.delete()
            }
            if (!file.renameTo(target)) {
                file.copyTo(target, overwrite = true)
                file.delete()
            }
        }
        file = target
    }
    if (!file.isFile || file.length() < WavWriter.HEADER_BYTES) return null

    val length = file.length()
    val actualData = WavWriter.recoveredDataSize(length)
    if (actualData <= 0L) return null

    val headerDataSize = RandomAccessFile(file, "r").use { raf ->
        if (raf.length() < WavWriter.HEADER_BYTES) return@use -1L
        raf.seek(40)
        readIntLE(raf).toLong() and 0xFFFF_FFFFL
    }
    if (headerDataSize < 0L) return null

    if (WavWriter.needsHeaderRewrite(length, headerDataSize)) {
        RandomAccessFile(file, "rw").use { raf ->
            writeDesktopWavHeader(raf, actualData, sampleRateHz)
        }
    }

    return WavFinish(
        absolutePath = file.absolutePath,
        durationSeconds = WavWriter.durationSeconds(actualData, sampleRateHz),
        sizeBytes = file.length(),
    )
}

private fun writeDesktopWavHeader(
    raf: RandomAccessFile,
    dataSize: Long,
    sampleRateHz: Int,
) {
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
}

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

private fun readIntLE(raf: RandomAccessFile): Int {
    val b0 = raf.read()
    val b1 = raf.read()
    val b2 = raf.read()
    val b3 = raf.read()
    if (b0 < 0 || b1 < 0 || b2 < 0 || b3 < 0) return -1
    return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
}
