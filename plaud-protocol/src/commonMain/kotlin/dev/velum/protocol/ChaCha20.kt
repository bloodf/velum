package dev.velum.protocol

/**
 * Plain ChaCha20 (RFC 8439, no Poly1305) for PLAUD.AI file bodies.
 *
 * Not JCA, which can't do this reliably on Android. `ChaCha20ParameterSpec` is public API only from 35, while
 * minSdk is 26: on API 34 its constructor throws NoSuchMethodError. Conscrypt 2.5.2, the version we can inspect,
 * ignores that spec and silently uses a random nonce, so a decrypted file is garbage with no error.
 */
object ChaCha20 {
    private const val BLOCK_BYTES = 64

    /** Encrypts or decrypts [data], starting at block [counter]. */
    fun xor(key: ByteArray, nonce: ByteArray, counter: Long, data: ByteArray): ByteArray {
        require(key.size == 32) { "ChaCha20 key must be 32 bytes" }
        require(nonce.size == 12) { "ChaCha20 nonce must be 12 bytes" }
        require(counter in 0..0xFFFF_FFFFL) { "ChaCha20 counter out of range" }
        val state = IntArray(16)
        state[0] = 0x61707865
        state[1] = 0x3320646e
        state[2] = 0x79622d32
        state[3] = 0x6b206574
        for (i in 0 until 8) state[4 + i] = readInt(key, i * 4)
        state[12] = counter.toInt()
        for (i in 0 until 3) state[13 + i] = readInt(nonce, i * 4)

        val out = ByteArray(data.size)
        val block = IntArray(16)
        var offset = 0
        while (offset < data.size) {
            state.copyInto(block)
            repeat(10) {
                quarterRound(block, 0, 4, 8, 12)
                quarterRound(block, 1, 5, 9, 13)
                quarterRound(block, 2, 6, 10, 14)
                quarterRound(block, 3, 7, 11, 15)
                quarterRound(block, 0, 5, 10, 15)
                quarterRound(block, 1, 6, 11, 12)
                quarterRound(block, 2, 7, 8, 13)
                quarterRound(block, 3, 4, 9, 14)
            }
            for (i in 0 until 16) block[i] += state[i]
            val length = minOf(BLOCK_BYTES, data.size - offset)
            for (i in 0 until length) {
                val keystream = block[i ushr 2] ushr ((i and 3) shl 3)
                out[offset + i] = (data[offset + i].toInt() xor keystream).toByte()
            }
            state[12]++
            offset += length
        }
        return out
    }

    private fun readInt(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or
            ((bytes[at + 3].toInt() and 0xFF) shl 24)

    private fun quarterRound(x: IntArray, a: Int, b: Int, c: Int, d: Int) {
        x[a] += x[b]
        x[d] = (x[d] xor x[a]).rotateLeft(16)
        x[c] += x[d]
        x[b] = (x[b] xor x[c]).rotateLeft(12)
        x[a] += x[b]
        x[d] = (x[d] xor x[a]).rotateLeft(8)
        x[c] += x[d]
        x[b] = (x[b] xor x[c]).rotateLeft(7)
    }
}
