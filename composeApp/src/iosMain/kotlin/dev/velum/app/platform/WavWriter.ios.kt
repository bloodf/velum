package dev.velum.app.platform

internal actual fun openWavFile(absolutePath: String): WavFileHandle = object : WavFileHandle {
    override fun writeWavHeader(dataSize: Long, sampleRateHz: Int) = Unit
    override fun append(bytes: ByteArray) = Unit
    override fun close() = Unit
    override fun length(): Long = 0
}
