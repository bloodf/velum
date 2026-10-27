package dev.velum.app.platform

/**
 * Normalize arbitrary imported audio to PCM16 LE mono WAV at [targetSampleRateHz]
 * for the shared ASR / diarization / summary pipeline.
 */
data class NormalizedAudio(
    val wavPath: String,
    val sampleRateHz: Int,
    val durationMs: Long,
    val sourceMime: String?,
    val contentSha256: String,
)

expect class AudioNormalizer() {
    /**
     * @param sourcePath absolute path to a copied import file
     * @return normalized WAV under the app recordings/imports directory
     */
    suspend fun normalizeToWav(
        sourcePath: String,
        targetSampleRateHz: Int = 16_000,
    ): NormalizedAudio
}

/** Extensions / MIME types Velum accepts for external import. */
object ImportAudioFormats {
    val extensions: Set<String> = setOf(
        "mp3", "m4a", "aac", "wav", "wave", "ogg", "opus", "flac",
        "amr", "3gp", "3gpp", "webm", "mp4", "m4b", "caf",
    )

    val mimeTypes: Array<String> = arrayOf(
        "audio/*",
        "audio/mpeg",
        "audio/mp4",
        "audio/aac",
        "audio/wav",
        "audio/x-wav",
        "audio/ogg",
        "audio/opus",
        "audio/flac",
        "audio/amr",
        "audio/3gpp",
        "audio/webm",
        "video/mp4", // audio-only mp4
        "application/ogg",
        "*/*",
    )

    fun isSupportedFileName(name: String): Boolean {
        val ext = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return ext in extensions
    }
}
