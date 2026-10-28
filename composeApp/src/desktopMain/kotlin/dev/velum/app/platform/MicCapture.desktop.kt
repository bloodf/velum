package dev.velum.app.platform

actual fun createMicCapture(): MicCapture = UnsupportedMicCapture

object UnsupportedMicCapture : MicCapture {
    override val isSupported: Boolean = false
    override val isCapturing: Boolean = false
    override val isPaused: Boolean = false
    override val markers: List<MicMarker> = emptyList()
    override var onPcmChunk: ((ByteArray) -> Unit)? = null

    override suspend fun start(
        absolutePath: String,
        inputSource: MicInputSource,
    ): MicStartResult = MicStartResult.Unsupported

    override suspend fun pause(): MicPauseResult = MicPauseResult.Unsupported

    override suspend fun resume(): MicPauseResult = MicPauseResult.Unsupported

    override suspend fun addMarker(label: String): MicMarkerResult = MicMarkerResult.Unsupported

    override suspend fun stop(): MicStopResult = MicStopResult.Unsupported
}
