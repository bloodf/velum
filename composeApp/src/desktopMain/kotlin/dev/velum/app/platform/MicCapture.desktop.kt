package dev.velum.app.platform

actual fun createMicCapture(): MicCapture = UnsupportedMicCapture

object UnsupportedMicCapture : MicCapture {
    override val isSupported: Boolean = false
    override val isCapturing: Boolean = false
    override val isPaused: Boolean = false
    override val markers: List<MicMarker> = emptyList()
    override var onPcmChunk: ((ByteArray) -> Unit)? = null

    override fun start(
        absolutePath: String,
        inputSource: MicInputSource,
    ): MicStartResult = MicStartResult.Unsupported

    override fun pause(): MicPauseResult = MicPauseResult.Unsupported

    override fun resume(): MicPauseResult = MicPauseResult.Unsupported

    override fun addMarker(label: String): MicMarkerResult = MicMarkerResult.Unsupported

    override fun stop(): MicStopResult = MicStopResult.Unsupported
}
