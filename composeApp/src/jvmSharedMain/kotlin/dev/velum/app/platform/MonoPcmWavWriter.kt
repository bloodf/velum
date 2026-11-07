package dev.velum.app.platform

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Streams interleaved PCM16 chunks into a mono WAV at [targetRate]: each chunk is downmixed and linearly
 * resampled as it arrives, so time is linear in the input length and memory is bounded by one chunk (#319).
 * The resampled values equal the former whole-buffer code (`toMonoPcm16` then `resamplePcm16`); the WAV header is
 * written as a placeholder and patched in [finish].
 *
 * When constructed with a custom [sink] (and optional null [file]), output is streamed directly to that sink.
 */
internal class MonoPcmWavWriter(
    private val file: File?,
    private val targetRate: Int,
    private val sink: OutputStream = BufferedOutputStream(FileOutputStream(checkNotNull(file)), OUT_BUFFER_BYTES),
    private val writeHeaderPlaceholder: Boolean = (file != null),
) : Closeable {
    constructor(file: File, targetRate: Int) : this(
        file = file,
        targetRate = targetRate,
        sink = BufferedOutputStream(FileOutputStream(file), OUT_BUFFER_BYTES),
        writeHeaderPlaceholder = true,
    )

    private val out = sink
    private var channels = 1
    private var sourceRate = targetRate
    private var leftover = ByteArray(0) // an incomplete trailing frame of the previous chunk
    private var tail = ShortArray(0) // the last few mono samples: outputs near the end need them (see tailSize)
    private var monoTotal = 0L // mono samples received in this format stage
    private var outIndex = 0L // output samples produced in this format stage
    private val pending = ByteBuffer.allocate(PENDING_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    private var closed = false

    /** Bytes of PCM written so far (excluding the header). */
    var pcmBytesWritten = 0L
        private set

    init {
        if (writeHeaderPlaceholder) {
            out.write(ByteArray(HEADER_BYTES)) // patched in finish()
        }
    }

    /** Declares the format of the chunks that follow. A change finishes the previous format's stage first. */
    fun setFormat(sourceRateHz: Int, channelCount: Int) {
        require(sourceRateHz > 0 && channelCount > 0) { "bad audio format: $sourceRateHz Hz, $channelCount channels" }
        if (sourceRateHz == sourceRate && channelCount == channels) return
        endStage()
        sourceRate = sourceRateHz
        channels = channelCount
    }

    fun write(chunk: ByteArray, length: Int = chunk.size) {
        check(!closed) { "writer is closed" }
        if (length == 0) return
        val frameBytes = 2 * channels
        val total = leftover.size + length
        val frames = total / frameBytes
        val src = if (leftover.isEmpty()) {
            ByteBuffer.wrap(chunk, 0, length)
        } else {
            ByteBuffer.allocate(total).put(leftover).put(chunk, 0, length).also { it.flip() }
        }.order(ByteOrder.LITTLE_ENDIAN)
        val mono = ShortArray(frames)
        for (f in 0 until frames) {
            var sum = 0
            repeat(channels) { sum += src.short.toInt() }
            mono[f] = (sum / channels).toShort()
        }
        leftover = ByteArray(total - frames * frameBytes).also { src.get(it) }
        emit(mono)
    }

    /** Flushes any buffered samples in [pending] to [sink]. */
    fun flush() {
        check(!closed) { "writer is closed" }
        flushPending()
        out.flush()
    }

    /** Input samples kept back: enough for every output the end of the stream can still ask for. */
    private fun tailSize(): Int = (sourceRate + targetRate - 1) / targetRate + 3

    private fun emit(mono: ShortArray) {
        if (mono.isEmpty()) return
        if (sourceRate == targetRate) {
            for (s in mono) putSample(s.toInt())
            monoTotal += mono.size
            outIndex += mono.size
            return
        }
        val buf = tail + mono
        val base = monoTotal - tail.size // global index of buf[0]
        val totalAfter = monoTotal + mono.size
        val last = totalAfter - 1
        // An output is only final when it is below the output length for ANY longer stream: floor(total * to / from)
        // never shrinks, so this bound is safe now. The rest wait for endStage, which knows the real length.
        val safeLen = (totalAfter * targetRate) / sourceRate
        while (outIndex < safeLen) {
            val srcIndex = outIndex.toDouble() * sourceRate / targetRate
            val i0 = srcIndex.toLong()
            if (i0 + 1 > last) break
            check(i0 >= base) { "resampler lost its history" }
            putInterpolated(buf[(i0 - base).toInt()].toInt(), buf[(i0 + 1 - base).toInt()].toInt(), srcIndex - i0)
            outIndex++
        }
        tail = buf.copyOfRange((buf.size - tailSize()).coerceAtLeast(0), buf.size)
        monoTotal = totalAfter
    }

    /** Emits the outputs that need the clamped final sample, then resets the per-format state. */
    private fun endStage() {
        if (sourceRate != targetRate && monoTotal > 0) {
            val outLen = ((monoTotal * targetRate) / sourceRate).coerceAtLeast(1)
            val lastIndex = monoTotal - 1
            val base = monoTotal - tail.size
            fun at(global: Long): Int {
                check(global >= base) { "resampler lost its history" }
                return tail[(global - base).toInt()].toInt()
            }
            while (outIndex < outLen) {
                val srcIndex = outIndex.toDouble() * sourceRate / targetRate
                val i0 = srcIndex.toLong().coerceIn(0L, lastIndex)
                val i1 = (i0 + 1).coerceAtMost(lastIndex)
                putInterpolated(at(i0), at(i1), srcIndex - i0)
                outIndex++
            }
        }
        leftover = ByteArray(0)
        tail = ShortArray(0)
        monoTotal = 0
        outIndex = 0
    }

    private fun putInterpolated(a: Int, b: Int, frac: Double) {
        val s = a * (1.0 - frac) + b * frac
        putSample(s.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()))
    }

    private fun putSample(s: Int) {
        if (!pending.hasRemaining()) flushPending()
        pending.putShort(s.toShort())
    }

    private fun flushPending() {
        val count = pending.position()
        if (count > 0) {
            out.write(pending.array(), 0, count)
            pcmBytesWritten += count
            pending.clear()
        }
    }

    /** Flushes everything and patches the WAV header. The file is complete only after this returns. */
    fun finish() {
        check(!closed) { "writer is closed" }
        endStage()
        flushPending()
        out.flush()
        out.close()
        closed = true
        file?.let { targetFile ->
            RandomAccessFile(targetFile, "rw").use { raf ->
                raf.seek(0)
                raf.write(header(targetRate, pcmBytesWritten))
                raf.fd.sync()
            }
        }
    }

    /** Closes without a valid header (error path); the caller deletes the file. */
    override fun close() {
        if (!closed) {
            closed = true
            runCatching { out.close() }
        }
    }

    private companion object {
        const val HEADER_BYTES = 44
        const val OUT_BUFFER_BYTES = 64 * 1024
        const val PENDING_BYTES = 32 * 1024

        fun header(sampleRateHz: Int, dataSize: Long): ByteArray {
            require(dataSize <= 0xFFFF_FFFFL - 36) { "audio too long for a WAV file" }
            return ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt((36 + dataSize).toInt()); put("WAVE".toByteArray())
                put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
                putInt(sampleRateHz); putInt(sampleRateHz * 2); putShort(2); putShort(16)
                put("data".toByteArray()); putInt(dataSize.toInt())
            }.array()
        }
    }
}

/**
 * An [OutputStream] that writes to an optional backing [fileOutputStream] and simultaneously
 * forwards written chunks to an [onChunk] callback.
 */
internal class PcmChunkSink(
    private val fileOutputStream: OutputStream? = null,
    private val onChunk: ((ByteArray) -> Unit)? = null,
) : OutputStream() {
    override fun write(b: Int) {
        val single = byteArrayOf(b.toByte())
        write(single, 0, 1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        fileOutputStream?.write(b, off, len)
        if (onChunk != null) {
            onChunk.invoke(b.copyOfRange(off, off + len))
        }
    }

    override fun flush() {
        fileOutputStream?.flush()
    }

    override fun close() {
        fileOutputStream?.close()
    }
}

/** Runs every step even if an earlier one throws; never throws (cleanup must not mask the original failure). */
internal fun releaseAll(vararg steps: () -> Unit) {
    for (step in steps) runCatching(step)
}
