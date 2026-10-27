package dev.velum.app.platform

actual class AudioNormalizer actual constructor() {
    actual suspend fun normalizeToWav(
        sourcePath: String,
        targetSampleRateHz: Int,
    ): NormalizedAudio {
        error("Audio normalize on iOS lands with M3; use Android for multi-format import.")
    }
}
