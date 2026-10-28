package dev.velum.ai.audio

/**
 * Minimal PCM s16le → WAV container helper for OpenAI-compatible `/audio/transcriptions`.
 * The recording pipeline decodes to raw PCM; remote ASR needs a real container, not naked samples.
 */
object PcmWav {
    /** 44-byte PCM WAV header only (no data payload) for streaming writes. */
    fun header(
        dataSize: Int,
        sampleRateHz: Int,
        channels: Int = 1,
        bitsPerSample: Int = 16,
    ): ByteArray {
        require(dataSize >= 0) { "dataSize must be non-negative" }
        require(sampleRateHz > 0) { "sampleRateHz must be positive" }
        require(channels > 0) { "channels must be positive" }
        require(bitsPerSample % 8 == 0) { "bitsPerSample must be a multiple of 8" }
        // Classic RIFF WAV uses 32-bit chunk sizes; reject near-2 GiB payloads
        // before `36 + dataSize` wraps Int.
        val riffChunkSize = 36L + dataSize.toLong()
        require(riffChunkSize <= Int.MAX_VALUE.toLong()) {
            "WAV dataSize $dataSize exceeds 32-bit RIFF limit"
        }
        val byteRate = sampleRateHz.toLong() * channels * (bitsPerSample / 8)
        require(byteRate <= Int.MAX_VALUE.toLong()) { "WAV byteRate overflows Int" }
        val blockAlign = (channels * bitsPerSample / 8).toShort()
        val out = ByteArray(44)
        writeAscii(out, 0, "RIFF")
        writeIntLe(out, 4, riffChunkSize.toInt())
        writeAscii(out, 8, "WAVE")
        writeAscii(out, 12, "fmt ")
        writeIntLe(out, 16, 16) // PCM fmt chunk size
        writeShortLe(out, 20, 1) // PCM format
        writeShortLe(out, 22, channels.toShort())
        writeIntLe(out, 24, sampleRateHz)
        writeIntLe(out, 28, byteRate.toInt())
        writeShortLe(out, 32, blockAlign)
        writeShortLe(out, 34, bitsPerSample.toShort())
        writeAscii(out, 36, "data")
        writeIntLe(out, 40, dataSize)
        return out
    }

    fun encode(
        pcmS16le: ByteArray,
        sampleRateHz: Int,
        channels: Int = 1,
        bitsPerSample: Int = 16,
    ): ByteArray {
        val total = 44L + pcmS16le.size.toLong()
        require(total <= Int.MAX_VALUE.toLong()) {
            "WAV payload ${pcmS16le.size} exceeds 32-bit size limit"
        }
        val header = header(
            dataSize = pcmS16le.size,
            sampleRateHz = sampleRateHz,
            channels = channels,
            bitsPerSample = bitsPerSample,
        )
        val out = ByteArray(total.toInt())
        header.copyInto(out, 0)
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
