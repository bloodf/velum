package dev.velum.app.platform

/**
 * Platform microphone capture. Android writes 16 kHz mono PCM WAV under app files.
 * Desktop/iOS return [MicCaptureResult.Unsupported].
 */
interface MicCapture {
    val isSupported: Boolean
    /** True while AudioRecord (or stub) is actively writing. */
    val isCapturing: Boolean

    /**
     * Optional PCM16 LE mono callback while capturing (e.g. live streaming ASR).
     * Invoked from the capture thread; keep work cheap.
     */
    var onPcmChunk: ((ByteArray) -> Unit)?

    /**
     * Begin capture to [absolutePath] (WAV). Fails if already capturing or unsupported.
     */
    fun start(absolutePath: String): MicStartResult

    /**
     * Stop capture and finalize the WAV header. Returns path + duration/size on success.
     */
    fun stop(): MicStopResult
}

sealed class MicStartResult {
    data object Started : MicStartResult()
    data class Failed(val message: String) : MicStartResult()
    data object Unsupported : MicStartResult()
    data object PermissionRequired : MicStartResult()
}

sealed class MicStopResult {
    data class Stopped(
        val absolutePath: String,
        val durationSeconds: Long,
        val sizeBytes: Long,
    ) : MicStopResult()
    data class Failed(val message: String) : MicStopResult()
    data object NotCapturing : MicStopResult()
    data object Unsupported : MicStopResult()
}

expect fun createMicCapture(): MicCapture
