package dev.velum.app.platform

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual class AudioNormalizer actual constructor() {

    actual suspend fun normalizeToWav(
        sourcePath: String,
        targetSampleRateHz: Int,
    ): NormalizedAudio = withContext(Dispatchers.IO) {
        val source = File(sourcePath)
        require(source.isFile && source.length() > 0L) { "Missing import: $sourcePath" }
        val hash = sha256File(sourcePath)
        val mime = guessMime(source.name)
        val outDir = File(source.parentFile, "normalized").apply { mkdirs() }
        val outFile = File(outDir, "${source.nameWithoutExtension}_$targetSampleRateHz.wav")

        // Fast path: already WAV at target rate — copy / rewrite header only.
        if (source.extension.equals("wav", true) || source.extension.equals("wave", true)) {
            val bytes = source.readBytes()
            if (looksLikeWav(bytes)) {
                val pcm = stripWavPcm(bytes)
                if (pcm.sampleRateHz == targetSampleRateHz && pcm.channels == 1) {
                    writeWav(outFile, pcm.pcm, targetSampleRateHz)
                    val durationMs = (pcm.pcm.size / 2.0 / targetSampleRateHz * 1000.0).toLong()
                    return@withContext NormalizedAudio(
                        wavPath = outFile.absolutePath,
                        sampleRateHz = targetSampleRateHz,
                        durationMs = durationMs,
                        sourceMime = mime,
                        contentSha256 = hash,
                    )
                }
            }
        }

        val pcm = decodeWithMediaCodec(source, targetSampleRateHz)
        writeWav(outFile, pcm, targetSampleRateHz)
        val durationMs = (pcm.size / 2.0 / targetSampleRateHz * 1000.0).toLong()
        NormalizedAudio(
            wavPath = outFile.absolutePath,
            sampleRateHz = targetSampleRateHz,
            durationMs = durationMs.coerceAtLeast(durationHintMs(sourcePath)),
            sourceMime = mime,
            contentSha256 = hash,
        )
    }

    private fun durationHintMs(path: String): Long {
        return runCatching {
            val r = MediaMetadataRetriever()
            r.setDataSource(path)
            val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            r.release()
            ms ?: 0L
        }.getOrDefault(0L)
    }

    private fun decodeWithMediaCodec(source: File, targetSampleRateHz: Int): ByteArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(source.absolutePath)
        val track = (0 until extractor.trackCount).firstOrNull { i ->
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            mime.startsWith("audio/")
        } ?: error("No audio track in ${source.name}")
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val mime = format.getString(MediaFormat.KEY_MIME)
            ?: error("Missing MIME on audio track")
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val pcmChunks = ArrayList<ByteArray>()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var sourceRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        } else {
            targetSampleRateHz
        }
        var channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        } else {
            1
        }

        while (!outputDone) {
            if (!inputDone) {
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val buffer = codec.getInputBuffer(inIndex)!!
                    buffer.clear()
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(
                            inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(
                            inIndex, 0, sampleSize, extractor.sampleTime, 0,
                        )
                        extractor.advance()
                    }
                }
            }
            val outIndex = codec.dequeueOutputBuffer(info, 10_000)
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val outFormat = codec.outputFormat
                    if (outFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sourceRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                    if (outFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                }
                outIndex >= 0 -> {
                    val outBuf = codec.getOutputBuffer(outIndex)!!
                    if (info.size > 0) {
                        val chunk = ByteArray(info.size)
                        outBuf.position(info.offset)
                        outBuf.get(chunk)
                        pcmChunks.add(chunk)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                }
            }
        }
        codec.stop()
        codec.release()
        extractor.release()

        val interleaved = pcmChunks.fold(ByteArray(0)) { acc, b -> acc + b }
        val mono = toMonoPcm16(interleaved, channels)
        return resamplePcm16(mono, sourceRate, targetSampleRateHz)
    }
}

private fun guessMime(name: String): String? {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "mp3" -> "audio/mpeg"
        "m4a", "mp4", "m4b" -> "audio/mp4"
        "aac" -> "audio/aac"
        "wav", "wave" -> "audio/wav"
        "ogg" -> "audio/ogg"
        "opus" -> "audio/opus"
        "flac" -> "audio/flac"
        "amr" -> "audio/amr"
        "3gp", "3gpp" -> "audio/3gpp"
        "webm" -> "audio/webm"
        else -> null
    }
}

private fun looksLikeWav(bytes: ByteArray): Boolean =
    bytes.size >= 12 &&
        bytes[0] == 'R'.code.toByte() &&
        bytes[1] == 'I'.code.toByte() &&
        bytes[2] == 'F'.code.toByte() &&
        bytes[3] == 'F'.code.toByte()

private data class WavPcm(val pcm: ByteArray, val sampleRateHz: Int, val channels: Int)

private fun stripWavPcm(bytes: ByteArray): WavPcm {
    var offset = 12
    var sampleRate = 16_000
    var channels = 1
    var bits = 16
    var dataOffset = -1
    var dataSize = 0
    while (offset + 8 <= bytes.size) {
        val id = bytes.decodeToString(offset, offset + 4)
        val size = ByteBuffer.wrap(bytes, offset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val next = offset + 8 + size
        when (id) {
            "fmt " -> {
                val bb = ByteBuffer.wrap(bytes, offset + 8, size).order(ByteOrder.LITTLE_ENDIAN)
                bb.short // format
                channels = bb.short.toInt() and 0xFFFF
                sampleRate = bb.int
                bb.int // byte rate
                bb.short // block align
                bits = bb.short.toInt() and 0xFFFF
            }
            "data" -> {
                dataOffset = offset + 8
                dataSize = size
            }
        }
        offset = if (size % 2 == 1) next + 1 else next
    }
    require(dataOffset >= 0) { "WAV missing data chunk" }
    require(bits == 16) { "Only PCM16 WAV supported in fast path (got $bits-bit)" }
    val pcm = bytes.copyOfRange(dataOffset, min(dataOffset + dataSize, bytes.size))
    return WavPcm(pcm, sampleRate, channels)
}

private fun toMonoPcm16(interleaved: ByteArray, channels: Int): ByteArray {
    if (channels <= 1) return interleaved
    val samples = interleaved.size / 2
    val frames = samples / channels
    val out = ByteArray(frames * 2)
    val bb = ByteBuffer.wrap(interleaved).order(ByteOrder.LITTLE_ENDIAN)
    val outBb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
    repeat(frames) {
        var sum = 0
        repeat(channels) { sum += bb.short.toInt() }
        outBb.putShort((sum / channels).toShort())
    }
    return out
}

private fun resamplePcm16(input: ByteArray, fromRate: Int, toRate: Int): ByteArray {
    if (fromRate == toRate || input.isEmpty()) return input
    val inSamples = ShortArray(input.size / 2)
    ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(inSamples)
    val outLen = ((inSamples.size.toLong() * toRate) / fromRate).toInt().coerceAtLeast(1)
    val outSamples = ShortArray(outLen)
    for (i in 0 until outLen) {
        val srcIndex = i.toDouble() * fromRate / toRate
        val i0 = srcIndex.toInt().coerceIn(0, inSamples.lastIndex)
        val i1 = (i0 + 1).coerceAtMost(inSamples.lastIndex)
        val frac = srcIndex - i0
        val s = inSamples[i0] * (1.0 - frac) + inSamples[i1] * frac
        outSamples[i] = s.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
    val out = ByteArray(outLen * 2)
    ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(outSamples)
    return out
}

private fun writeWav(file: File, pcm: ByteArray, sampleRateHz: Int) {
    FileOutputStream(file).use { out ->
        val channels = 1
        val bits = 16
        val byteRate = sampleRateHz * channels * bits / 8
        val dataSize = pcm.size
        val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray())
        buffer.putInt(36 + dataSize)
        buffer.put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16)
        buffer.putShort(1) // PCM
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRateHz)
        buffer.putInt(byteRate)
        buffer.putShort((channels * bits / 8).toShort())
        buffer.putShort(bits.toShort())
        buffer.put("data".toByteArray())
        buffer.putInt(dataSize)
        buffer.put(pcm)
        out.write(buffer.array())
    }
}
