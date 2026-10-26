package dev.velum.ai.local

/**
 * Convert little-endian 16-bit PCM bytes to float samples in [-1, 1].
 */
fun pcm16LeToFloats(pcm: ByteArray): FloatArray {
    require(pcm.size % 2 == 0) { "PCM16 byte length must be even (got ${pcm.size})" }
    val out = FloatArray(pcm.size / 2)
    var i = 0
    var o = 0
    while (i < pcm.size) {
        val lo = pcm[i].toInt() and 0xFF
        val hi = pcm[i + 1].toInt()
        val sample = ((hi shl 8) or lo).toShort()
        out[o++] = sample / 32768.0f
        i += 2
    }
    return out
}

fun sliceFloats(samples: FloatArray, startSample: Int, endSample: Int): FloatArray {
    val start = startSample.coerceIn(0, samples.size)
    val end = endSample.coerceIn(start, samples.size)
    return samples.copyOfRange(start, end)
}
