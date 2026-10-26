package dev.velum.ai.pipeline

/**
 * Fixed-capacity PCM16 ring for live streaming ASR.
 * Drops oldest bytes when full instead of growing without bound (Y17).
 */
class BoundedPcmRingBuffer(
    maxBytes: Int,
) {
    init {
        require(maxBytes >= 2) { "maxBytes must be ≥ 2 (got $maxBytes)" }
        require(maxBytes % 2 == 0) { "maxBytes must be even for PCM16 (got $maxBytes)" }
    }

    private val buf = ByteArray(maxBytes)
    private var start = 0
    private var length = 0

    val capacity: Int get() = buf.size
    val size: Int get() = length

    fun clear() {
        start = 0
        length = 0
    }

    fun write(data: ByteArray) {
        if (data.isEmpty()) return
        var srcOff = 0
        var remaining = data.size
        if (remaining >= buf.size) {
            srcOff = remaining - buf.size
            remaining = buf.size
            data.copyInto(buf, 0, srcOff, srcOff + remaining)
            start = 0
            length = remaining - (remaining % 2)
            return
        }
        while (remaining > 0) {
            val free = buf.size - length
            if (free == 0) {
                val drop = minOf(remaining, length).let { d -> d - (d % 2) }.coerceAtLeast(2)
                start = (start + drop) % buf.size
                length -= drop
            }
            val writePos = (start + length) % buf.size
            val contiguous = minOf(remaining, buf.size - writePos, buf.size - length)
            if (contiguous <= 0) continue
            data.copyInto(buf, writePos, srcOff, srcOff + contiguous)
            srcOff += contiguous
            remaining -= contiguous
            length += contiguous
        }
        if (length % 2 != 0) {
            start = (start + 1) % buf.size
            length--
        }
    }

    fun toByteArray(): ByteArray {
        if (length == 0) return ByteArray(0)
        val out = ByteArray(length)
        val first = minOf(length, buf.size - start)
        buf.copyInto(out, 0, start, start + first)
        if (first < length) {
            buf.copyInto(out, first, 0, length - first)
        }
        return out
    }

    /** Copy a half-open byte range relative to the logical start of the buffer. */
    fun copyRange(from: Int, to: Int): ByteArray {
        val f = from.coerceIn(0, length)
        val t = to.coerceIn(f, length)
        if (t <= f) return ByteArray(0)
        val out = ByteArray(t - f)
        for (i in f until t) {
            out[i - f] = buf[(start + i) % buf.size]
        }
        return out
    }

    companion object {
        fun forMaxDuration(sampleRateHz: Int, maxSeconds: Int): BoundedPcmRingBuffer {
            val bytes = sampleRateHz.coerceAtLeast(1) * 2 * maxSeconds.coerceAtLeast(1)
            val even = bytes - (bytes % 2)
            return BoundedPcmRingBuffer(even.coerceAtLeast(2))
        }
    }
}
