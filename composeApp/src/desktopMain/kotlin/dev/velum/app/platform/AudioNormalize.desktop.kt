package dev.velum.app.platform

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Desktop normalizer: WAV/Ogg/Opus passthrough into PCM WAV.
 * Compressed formats (mp3/m4a/…) are handled on Android via MediaCodec.
 */
actual class AudioNormalizer actual constructor() {
    actual suspend fun normalizeToWav(
        sourcePath: String,
        targetSampleRateHz: Int,
    ): NormalizedAudio = withContext(Dispatchers.IO) {
        val source = File(sourcePath)
        require(source.isFile) { "Missing import: $sourcePath" }
        val hash = sha256File(sourcePath)
        val outDir = File(source.parentFile, "normalized").apply { mkdirs() }
        val outFile = File(outDir, "${source.nameWithoutExtension}_$targetSampleRateHz.wav")
        val name = source.name.lowercase()
        when {
            name.endsWith(".wav") || name.endsWith(".wave") -> {
                val bytes = source.readBytes()
                val pcm = stripWav(bytes)
                writeWav(outFile, pcm.pcm, pcm.sampleRateHz.takeIf { it > 0 } ?: targetSampleRateHz)
                val rate = pcm.sampleRateHz.takeIf { it > 0 } ?: targetSampleRateHz
                NormalizedAudio(
                    wavPath = outFile.absolutePath,
                    sampleRateHz = rate,
                    durationMs = (pcm.pcm.size / 2.0 / rate * 1000).toLong(),
                    sourceMime = "audio/wav",
                    contentSha256 = hash,
                )
            }
            name.endsWith(".ogg") || name.endsWith(".opus") -> {
                // Leave as-is for RecordingProcessor Opus path; still return a copy path + hash.
                val dest = File(outDir, source.name)
                source.copyTo(dest, overwrite = true)
                NormalizedAudio(
                    wavPath = dest.absolutePath,
                    sampleRateHz = targetSampleRateHz,
                    durationMs = 0L,
                    sourceMime = "audio/ogg",
                    contentSha256 = hash,
                )
            }
            else -> error(
                "Desktop build cannot decode ${source.extension}; use Android for mp3/m4a/flac/amr/webm or convert to WAV first.",
            )
        }
    }
}

private data class WavPcm(val pcm: ByteArray, val sampleRateHz: Int)

private fun stripWav(bytes: ByteArray): WavPcm {
    var offset = 12
    var sampleRate = 16_000
    var dataOffset = -1
    var dataSize = 0
    while (offset + 8 <= bytes.size) {
        val id = bytes.decodeToString(offset, offset + 4)
        val size = ByteBuffer.wrap(bytes, offset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val next = offset + 8 + size
        if (id == "fmt ") {
            sampleRate = ByteBuffer.wrap(bytes, offset + 8 + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        } else if (id == "data") {
            dataOffset = offset + 8
            dataSize = size
        }
        offset = if (size % 2 == 1) next + 1 else next
    }
    require(dataOffset >= 0)
    return WavPcm(bytes.copyOfRange(dataOffset, min(dataOffset + dataSize, bytes.size)), sampleRate)
}

private fun writeWav(file: File, pcm: ByteArray, sampleRateHz: Int) {
    val dataSize = pcm.size
    val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
    buffer.put("RIFF".toByteArray())
    buffer.putInt(36 + dataSize)
    buffer.put("WAVE".toByteArray())
    buffer.put("fmt ".toByteArray())
    buffer.putInt(16)
    buffer.putShort(1)
    buffer.putShort(1)
    buffer.putInt(sampleRateHz)
    buffer.putInt(sampleRateHz * 2)
    buffer.putShort(2)
    buffer.putShort(16)
    buffer.put("data".toByteArray())
    buffer.putInt(dataSize)
    buffer.put(pcm)
    file.writeBytes(buffer.array())
}
