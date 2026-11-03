package dev.velum.app.platform

import dev.velum.app.playback.SkipSilenceScanner
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Windowed access to the PCM16 data of a WAV file for skip-silence (#333). Opening reads only the header; scanning
 * reads a bounded window at a time, so a two-hour file never sits in memory.
 */
internal class WavPcmSource private constructor(
    private val file: File,
    val sampleRateHz: Int,
    private val channels: Int,
    private val dataOffset: Long,
    private val dataSize: Long,
) {
    /** File bytes read since opening (header excluded), for tests and diagnostics. */
    @Volatile var bytesRead = 0L
        private set

    /** The largest single read, in bytes. */
    @Volatile var largestRead = 0
        private set

    private val frameBytes = 2 * channels
    private val totalFrames = dataSize / frameBytes
    private val windowFrames = (WINDOW_BYTES / frameBytes).coerceAtLeast(1)

    /** Mono PCM16 LE of [count] frames from [startFrame], averaged across channels like the old code. */
    private fun readMono(startFrame: Long, count: Int): ByteArray {
        val raw = ByteArray(count * frameBytes)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(dataOffset + startFrame * frameBytes)
            raf.readFully(raw)
        }
        bytesRead += raw.size
        if (raw.size > largestRead) largestRead = raw.size
        if (channels == 1) return raw
        val src = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val out = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(count) {
            var sum = 0
            repeat(channels) { sum += src.short.toInt() }
            out.putShort((sum / channels).toShort())
        }
        return out.array()
    }

    /**
     * Same result as [SkipSilenceScanner.findSkipPastSilence] over the whole mono signal, but scanned one window
     * at a time from [fromMs] until the first audible sample.
     */
    fun findSkipPastSilence(
        fromMs: Long,
        minSilenceMs: Long = SkipSilenceScanner.DEFAULT_MIN_SILENCE_MS,
        energyThreshold: Int = SkipSilenceScanner.DEFAULT_ENERGY_THRESHOLD,
    ): SkipSilenceScanner.Result? {
        if (fromMs < 0L || totalFrames == 0L) return null
        val fromSample = ((fromMs * sampleRateHz) / 1000L).coerceIn(0L, totalFrames)
        if (fromSample >= totalFrames) return null
        var index = fromSample
        try {
            while (index < totalFrames) {
                val n = minOf(windowFrames.toLong(), totalFrames - index).toInt()
                val mono = readMono(index, n)
                var i = 0
                while (i < n && SkipSilenceScanner.isSilent(mono, i, energyThreshold)) i++
                index += i
                if (i < n) break
            }
        } catch (_: java.io.IOException) {
            return null // an unreadable file just means no skipping
        }
        val silentSamples = index - fromSample
        if (silentSamples <= 0) return null
        if ((silentSamples * 1000L) / sampleRateHz < minSilenceMs) return null
        val seekToMs = (index * 1000L) / sampleRateHz
        return if (seekToMs <= fromMs) null else SkipSilenceScanner.Result(seekToMs)
    }

    companion object {
        /** Bytes read per window, whatever the channel count. */
        const val WINDOW_BYTES = 128 * 1024

        /** More channels than any real file has; a corrupt header must not size a buffer. */
        const val MAX_CHANNELS = 32

        /** Null unless the file is a PCM16 WAV with a data chunk (the old loader's conditions). Reads the header only. */
        fun open(file: File): WavPcmSource? = try {
            RandomAccessFile(file, "r").use { raf ->
                val length = raf.length()
                if (length < 44L) return null
                val head = ByteArray(12).also { raf.readFully(it) }
                if (head.decodeToString(0, 4) != "RIFF") return null
                var sampleRate = 16_000
                var channels = 1
                var offset = 12L
                var dataOffset = -1L
                var dataSize = 0L
                val chunk = ByteArray(8)
                while (offset + 8 <= length) {
                    raf.seek(offset)
                    raf.readFully(chunk)
                    val id = chunk.decodeToString(0, 4)
                    val size = ByteBuffer.wrap(chunk, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFF_FFFFL
                    if (id == "fmt ") {
                        if (size < 16) return null
                        val fmt = ByteArray(16).also { raf.readFully(it) }
                        val bb = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                        val audioFormat = bb.short.toInt() and 0xFFFF
                        channels = bb.short.toInt() and 0xFFFF
                        sampleRate = bb.int
                        bb.position(14)
                        val bits = bb.short.toInt() and 0xFFFF
                        if (audioFormat != 1 || bits != 16 || channels !in 1..MAX_CHANNELS) return null
                    } else if (id == "data") {
                        dataOffset = offset + 8
                        dataSize = minOf(size, length - dataOffset)
                        break
                    }
                    offset += 8 + size + (size and 1L)
                }
                if (dataOffset < 0 || dataSize <= 0) return null
                WavPcmSource(file, sampleRate.coerceAtLeast(1), channels, dataOffset, dataSize)
            }
        } catch (_: Throwable) {
            null
        }
    }
}
