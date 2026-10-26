package dev.velum.protocol

/** Little-endian helpers for Plaud BLE framing. */
object Bytes {
    fun u16Le(value: Int): ByteArray =
        byteArrayOf(
            (value and 0xFF).toByte(),
            ((value ushr 8) and 0xFF).toByte(),
        )

    fun u32Le(value: Long): ByteArray =
        byteArrayOf(
            (value and 0xFF).toByte(),
            ((value ushr 8) and 0xFF).toByte(),
            ((value ushr 16) and 0xFF).toByte(),
            ((value ushr 24) and 0xFF).toByte(),
        )

    fun readU16Le(data: ByteArray, offset: Int): Int {
        require(offset + 1 < data.size) { "Need 2 bytes at $offset (size=${data.size})" }
        return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    fun readU32Le(data: ByteArray, offset: Int): Long {
        require(offset + 3 < data.size) { "Need 4 bytes at $offset (size=${data.size})" }
        return (data[offset].toLong() and 0xFF) or
            ((data[offset + 1].toLong() and 0xFF) shl 8) or
            ((data[offset + 2].toLong() and 0xFF) shl 16) or
            ((data[offset + 3].toLong() and 0xFF) shl 24)
    }

    fun hexToBytes(hex: String): ByteArray {
        val cleaned = hex.replace(Regex("\\s+"), "").lowercase()
        require(cleaned.length % 2 == 0) { "Hex length must be even" }
        return ByteArray(cleaned.length / 2) { i ->
            cleaned.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    fun bytesToHex(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): String {
        val sb = StringBuilder(length * 2)
        for (i in offset until offset + length) {
            sb.append(((data[i].toInt() and 0xFF) shr 4).toString(16))
            sb.append((data[i].toInt() and 0x0F).toString(16))
        }
        return sb.toString()
    }

    /** Trim trailing zero bytes the way the Tinnotech SDK does for writes. */
    fun trimTrailingZeros(data: ByteArray): ByteArray {
        var end = data.size
        while (end > 1 && data[end - 1] == 0.toByte()) end--
        return if (end == data.size) data else data.copyOf(end)
    }
}
