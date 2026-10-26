package dev.velum.app.platform

/**
 * Appends PCM16 LE mono samples and finalizes a standard WAV header on [finish].
 */
class WavWriter(
    absolutePath: String,
    private val sampleRateHz: Int = 16_000,
) {
    private val file = openWavFile(absolutePath)
    private var dataBytes: Long = 0
    private var closed = false
    val path: String = absolutePath

    init {
        file.writeWavHeader(dataSize = 0, sampleRateHz = sampleRateHz)
    }

    fun writePcm(pcm16Le: ByteArray) {
        if (closed || pcm16Le.isEmpty()) return
        file.append(pcm16Le)
        dataBytes += pcm16Le.size.toLong()
    }

    fun finish(): WavFinish {
        if (!closed) {
            file.writeWavHeader(dataSize = dataBytes, sampleRateHz = sampleRateHz)
            file.close()
            closed = true
        }
        val duration = if (sampleRateHz <= 0) 0L else dataBytes / (2L * sampleRateHz)
        return WavFinish(path, duration, file.length().coerceAtLeast(dataBytes + 44))
    }

    companion object {
        const val HEADER_BYTES = 44

        /**
         * True when [fileLength] has PCM past the 44-byte header but [headerDataSize]
         * does not match (0, stale, or truncated header after a crash).
         */
        fun needsHeaderRewrite(fileLength: Long, headerDataSize: Long): Boolean {
            if (fileLength < HEADER_BYTES) return false
            val actual = recoveredDataSize(fileLength)
            return headerDataSize != actual
        }

        fun recoveredDataSize(fileLength: Long): Long =
            (fileLength - HEADER_BYTES).coerceAtLeast(0)

        fun durationSeconds(dataBytes: Long, sampleRateHz: Int): Long =
            if (sampleRateHz <= 0) 0L else dataBytes / (2L * sampleRateHz)

        /**
         * Fix an incomplete / crashed WAV (or `.wav.partial`) from on-disk length.
         * Returns null when the path is missing or has no recoverable PCM.
         */
        fun recoverOrFinalize(
            absolutePath: String,
            sampleRateHz: Int = 16_000,
        ): WavFinish? = recoverOrFinalizeWav(absolutePath, sampleRateHz)
    }
}

data class WavFinish(
    val absolutePath: String,
    val durationSeconds: Long,
    val sizeBytes: Long,
)

internal interface WavFileHandle {
    fun writeWavHeader(dataSize: Long, sampleRateHz: Int)
    fun append(bytes: ByteArray)
    fun close()
    fun length(): Long
}

internal expect fun openWavFile(absolutePath: String): WavFileHandle

internal expect fun recoverOrFinalizeWav(
    absolutePath: String,
    sampleRateHz: Int,
): WavFinish?
