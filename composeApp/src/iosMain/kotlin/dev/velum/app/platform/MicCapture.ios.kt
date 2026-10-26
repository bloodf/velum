package dev.velum.app.platform

actual fun createMicCapture(): MicCapture = object : MicCapture {
    override val isSupported: Boolean = false
    override val isCapturing: Boolean = false
    override val isPaused: Boolean = false
    override var onPcmChunk: ((ByteArray) -> Unit)? = null
    override fun start(absolutePath: String): MicStartResult = MicStartResult.Unsupported
    override fun pause(): MicPauseResult = MicPauseResult.Unsupported
    override fun resume(): MicPauseResult = MicPauseResult.Unsupported
    override fun stop(): MicStopResult = MicStopResult.Unsupported
}
