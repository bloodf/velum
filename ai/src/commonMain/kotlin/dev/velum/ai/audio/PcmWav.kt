package dev.velum.ai.audio

/**
 * Minimal PCM s16le → WAV container helper for OpenAI-compatible `/audio/transcriptions`.
 * The recording pipeline decodes to raw PCM; remote ASR needs a real container, not naked samples.
 */
object PcmWav {
    fun encode(
        pcmS16le: ByteArray,
        sampleRateHz: Int,
        channels: Int = 1,
        bitsPerSample: Int = 16,
    ): ByteArray {
        require(sampleRateHz > 0) { "sampleRateHz must be positive" }
        require(channels > 0) { "channels must be positive" }
        require(bitsPerSample % 8 == 0) { "bitsPerSample must be a multiple of 8" }
        val byteRate = sampleRateHz * channels * bitsPerSample / 8
        val blockAlign = (channels * bitsPerSample / 8).toShort()
        val dataSize = pcmS16le.size
        val out = ByteArray(44 + dataSize)
        // RIFF header
        writeAscii(out, 0, "RIFF")
        writeIntLe(out, 4, 36 + dataSize)
        writeAscii(out, 8, "WAVE")
        // fmt chunk
        writeAscii(out, 12, "fmt ")
        writeIntLe(out, 16, 16) // PCM fmt chunk size
        writeShortLe(out, 20, 1) // PCM format
        writeShortLe(out, 22, channels.toShort())
        writeIntLe(out, 24, sampleRateHz)
        writeIntLe(out, 28, byteRate)
        writeShortLe(out, 32, blockAlign)
        writeShortLe(out, 34, bitsPerSample.toShort())
        // data chunk
        writeAscii(out, 36, "data")
        writeIntLe(out, 40, dataSize)
        pcmS16le.copyInto(out, 44)
        return out
    }

    fun looksLikeWav(bytes: ByteArray): Boolean =
        bytes.size >= 12 &&
            bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() &&
            bytes[9] == 'A'.code.toByte() &&
            bytes[10] == 'V'.code.toByte() &&
            bytes[11] == 'E'.code.toByte()

    fun looksLikeOgg(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 'O'.code.toByte() &&
            bytes[1] == 'g'.code.toByte() &&
            bytes[2] == 'g'.code.toByte() &&
            bytes[3] == 'S'.code.toByte()

    fun contentTypeForFilename(filename: String): String {
        val lower = filename.lowercase()
        return when {
            lower.endsWith(".wav") || lower.endsWith(".wave") -> "audio/wav"
            lower.endsWith(".mp3") -> "audio/mpeg"
            lower.endsWith(".m4a") || lower.endsWith(".mp4") -> "audio/mp4"
            lower.endsWith(".ogg") || lower.endsWith(".opus") -> "audio/ogg"
            lower.endsWith(".flac") -> "audio/flac"
            lower.endsWith(".webm") -> "audio/webm"
            else -> "application/octet-stream"
        }
    }

    private fun writeAscii(target: ByteArray, offset: Int, value: String) {
        for (i in value.indices) {
            target[offset + i] = value[i].code.toByte()
        }
    }

    private fun writeIntLe(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xff).toByte()
        target[offset + 1] = ((value ushr 8) and 0xff).toByte()
        target[offset + 2] = ((value ushr 16) and 0xff).toByte()
        target[offset + 3] = ((value ushr 24) and 0xff).toByte()
    }

    private fun writeShortLe(target: ByteArray, offset: Int, value: Short) {
        val v = value.toInt()
        target[offset] = (v and 0xff).toByte()
        target[offset + 1] = ((v ushr 8) and 0xff).toByte()
    }
}
