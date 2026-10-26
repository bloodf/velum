package dev.velum.app.platform

/**
 * Live PCM16 LE mono capture for streaming ASR.
 * Returns null when the platform has no mic path (desktop / iOS / no permission).
 */
expect fun createLivePcmCapture(): LivePcmCapture?

interface LivePcmCapture {
    /** Sample rate of emitted PCM (Hz). */
    val sampleRateHz: Int

    /**
     * Start capturing. [onPcm] may be invoked from a background thread.
     * Returns false if the mic could not start (permission / hardware).
     */
    fun start(onPcm: (ByteArray) -> Unit): Boolean

    fun stop()
}
