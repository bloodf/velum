package dev.velum.app.platform

actual fun createMicCapture(): MicCapture = object : MicCapture {
    override val isSupported: Boolean = false
    override val isCapturing: Boolean = false
    override var onPcmChunk: ((ByteArray) -> Unit)? = null
    override fun start(absolutePath: String): MicStartResult = MicStartResult.Unsupported
    override fun stop(): MicStopResult = MicStopResult.Unsupported
}
