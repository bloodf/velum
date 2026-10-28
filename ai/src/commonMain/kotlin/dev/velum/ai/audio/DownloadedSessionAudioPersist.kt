package dev.velum.ai.audio

/**
 * Turns decrypted Plaud session bodies into self-describing on-disk audio for
 * [dev.velum.ai.pipeline.RecordingProcessor].
 *
 * Note Pro / modern SoftAP decrypt to Ogg Opus (`OggS`). Rare exports are WAV.
 * Everything else (legacy raw PCM16, older Note raw Opus segments, ADPCM, etc.)
 * historically landed as `.bin` and was treated as mono PCM @ 16 kHz by the
 * processor — we now wrap that as a real WAV so streaming decode never sees
 * an opaque extension.
 */
object DownloadedSessionAudioPersist {
    /** Matches [DeviceCapabilities.sampleRatesHz] for Plaud 1.0 models and the old RP default. */
    const val DEFAULT_SAMPLE_RATE_HZ: Int = 16_000

    /** Matches the pre-#86 RecordingProcessor raw-PCM assumption (mono). */
    const val DEFAULT_CHANNELS: Int = 1

    data class Prepared(
        /** File extension without dot — always `ogg` or `wav` (never `bin`). */
        val extension: String,
        val bytes: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Prepared) return false
            return extension == other.extension && bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int = 31 * extension.hashCode() + bytes.contentHashCode()
    }

    /**
     * @param sampleRateHz used only when wrapping non-Ogg/non-WAV bytes as PCM WAV
     * @param channels used only for that WAV wrap (default mono)
     */
    fun prepare(
        decryptedBytes: ByteArray,
        sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ,
        channels: Int = DEFAULT_CHANNELS,
    ): Prepared = when {
        PcmWav.looksLikeOgg(decryptedBytes) -> Prepared(extension = "ogg", bytes = decryptedBytes)
        PcmWav.looksLikeWav(decryptedBytes) -> Prepared(extension = "wav", bytes = decryptedBytes)
        else -> Prepared(
            extension = "wav",
            bytes = PcmWav.encode(
                pcmS16le = decryptedBytes,
                sampleRateHz = sampleRateHz.coerceAtLeast(1),
                channels = channels.coerceIn(1, 2),
            ),
        )
    }
}
