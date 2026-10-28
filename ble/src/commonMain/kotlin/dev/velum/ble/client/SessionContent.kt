package dev.velum.ble.client

/**
 * Session file bytes addressable by offset without requiring the full payload in memory.
 * Used by the fake peripheral and multi-hour download tests.
 */
interface SessionContent {
    fun size(): Long

    /** Copy [length] bytes starting at [offset] into [dest] at [destPos]. */
    fun copyInto(offset: Long, length: Int, dest: ByteArray, destPos: Int = 0)

    fun slice(offset: Long, length: Int): ByteArray {
        val out = ByteArray(length)
        copyInto(offset, length, out, 0)
        return out
    }
}

/** In-memory content for small fixtures. */
class ByteArraySessionContent(
    private val bytes: ByteArray,
) : SessionContent {
    override fun size(): Long = bytes.size.toLong()

    override fun copyInto(offset: Long, length: Int, dest: ByteArray, destPos: Int) {
        require(offset >= 0 && length >= 0)
        require(offset + length <= bytes.size) {
            "slice offset=$offset length=$length exceeds size=${bytes.size}"
        }
        bytes.copyInto(dest, destPos, offset.toInt(), offset.toInt() + length)
    }
}

/**
 * Deterministic synthetic payload for multi-hour-sized transfers.
 * Byte at index i is `(i * 31 + seed) mod 256` — never materialises the full array.
 */
class SyntheticSessionContent(
    private val sizeBytes: Long,
    private val seed: Int = 17,
) : SessionContent {
    init {
        require(sizeBytes >= 0) { "sizeBytes must be >= 0" }
    }

    override fun size(): Long = sizeBytes

    override fun copyInto(offset: Long, length: Int, dest: ByteArray, destPos: Int) {
        require(offset >= 0 && length >= 0)
        require(offset + length <= sizeBytes) {
            "slice offset=$offset length=$length exceeds size=$sizeBytes"
        }
        var i = 0
        while (i < length) {
            dest[destPos + i] = byteAt(offset + i)
            i++
        }
    }

    fun byteAt(index: Long): Byte =
        (((index * 31) + seed) and 0xFF).toByte()

    companion object {
        /**
         * ~2 h of Note Pro Ogg Opus at ~32 kbps (4 KB/s).
         * Tests stream this; they must not allocate the full array.
         */
        const val TWO_HOUR_BYTES: Long = 2L * 3600L * 4000L
    }
}
