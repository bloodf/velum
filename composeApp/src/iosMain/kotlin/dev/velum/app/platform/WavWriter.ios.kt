package dev.velum.app.platform

internal actual fun openWavFile(absolutePath: String): WavFileHandle = object : WavFileHandle {
    override fun writeWavHeader(dataSize: Long, sampleRateHz: Int) = Unit
    override fun append(bytes: ByteArray) = Unit
    override fun close() = Unit
    override fun length(): Long = 0
}

internal actual fun recoverOrFinalizeWav(
    absolutePath: String,
    sampleRateHz: Int,
): WavFinish? = null

internal actual fun recoverDetailedWav(absolutePath: String, sampleRateHz: Int): WavRecoveryResult =
    WavRecoveryResult.Unsupported(absolutePath)

// Microphone capture is unsupported here; never infer that a user file is missing.
internal actual fun inspectMicDraftAudio(absolutePath: String): MicDraftAudioEvidence = MicDraftAudioEvidence.Unavailable
