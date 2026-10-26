package dev.velum.ble.client

/**
 * Accumulates BLE session download chunks without boxing each byte.
 * Prefer [SessionByteSink] for large transfers so callers can stream to a file.
 */
class SessionDownloadBuffer(
    private val maxBytes: Long,
) {
    private val chunks = ArrayList<ByteArray>()
    private var size: Long = 0L

    fun size(): Long = size

    fun write(data: ByteArray) {
        if (data.isEmpty()) return
        val next = size + data.size.toLong()
        require(next <= maxBytes) {
            "Session download exceeded ${maxBytes}-byte cap"
        }
        chunks += data.copyOf()
        size = next
    }

    fun toByteArray(): ByteArray {
        if (chunks.isEmpty()) return ByteArray(0)
        if (chunks.size == 1) return chunks[0]
        val out = ByteArray(size.toInt())
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(out, offset)
            offset += chunk.size
        }
        return out
    }

    fun clear() {
        chunks.clear()
        size = 0L
    }
}

fun interface SessionByteSink {
    fun write(data: ByteArray)
}
