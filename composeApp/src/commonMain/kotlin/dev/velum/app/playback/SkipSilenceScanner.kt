package dev.velum.app.playback

/**
 * Pure PCM16 LE mono silence detector for skip-silence playback.
 * When the contiguous silent run from [fromMs] exceeds [minSilenceMs], returns
 * the millisecond offset of the first audible sample after that run.
 */
object SkipSilenceScanner {
    const val DEFAULT_MIN_SILENCE_MS = 400L

    /** Absolute PCM16 sample magnitude treated as silence. */
    const val DEFAULT_ENERGY_THRESHOLD = 512

    data class Result(val seekToMs: Long)

    /**
     * @param pcm16LeMono little-endian PCM16 mono samples (no WAV header)
     * @param sampleRateHz sample rate of [pcm16LeMono]
     * @param fromMs playback position to inspect from
     * @return seek target past a silence region longer than [minSilenceMs], or null
     */
    fun findSkipPastSilence(
        pcm16LeMono: ByteArray,
        sampleRateHz: Int,
        fromMs: Long,
        minSilenceMs: Long = DEFAULT_MIN_SILENCE_MS,
        energyThreshold: Int = DEFAULT_ENERGY_THRESHOLD,
    ): Result? {
        if (sampleRateHz <= 0 || pcm16LeMono.size < 2 || fromMs < 0L) return null
        val sampleCount = pcm16LeMono.size / 2
        if (sampleCount == 0) return null

        val fromSample = ((fromMs * sampleRateHz) / 1000L)
            .toInt()
            .coerceIn(0, sampleCount)
        if (fromSample >= sampleCount) return null

        var index = fromSample
        while (index < sampleCount && isSilent(pcm16LeMono, index, energyThreshold)) {
            index++
        }
        val silentSamples = index - fromSample
        if (silentSamples <= 0) return null

        val silentMs = (silentSamples.toLong() * 1000L) / sampleRateHz
        if (silentMs < minSilenceMs) return null

        val seekToMs = (index.toLong() * 1000L) / sampleRateHz
        if (seekToMs <= fromMs) return null
        return Result(seekToMs)
    }

    /** True when the absolute sample energy at [sampleIndex] is below [energyThreshold]. */
    fun isSilent(
        pcm16LeMono: ByteArray,
        sampleIndex: Int,
        energyThreshold: Int = DEFAULT_ENERGY_THRESHOLD,
    ): Boolean {
        val offset = sampleIndex * 2
        if (offset + 1 >= pcm16LeMono.size) return true
        val lo = pcm16LeMono[offset].toInt() and 0xFF
        val hi = pcm16LeMono[offset + 1].toInt()
        val sample = ((hi shl 8) or lo).toShort().toInt()
        val energy = if (sample < 0) -sample else sample
        return energy < energyThreshold
    }
}
