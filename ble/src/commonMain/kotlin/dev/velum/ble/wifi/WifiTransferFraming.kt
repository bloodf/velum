package dev.velum.ble.wifi

/**
 * Length-prefixed Wi-Fi transfer framing (u32 BE then body).
 *
 * SoftAP phone listener rejects bad lengths, incomplete prefixes, and frames
 * larger than [MAX_FRAME_BYTES]. Callers must read the body with an exact-length
 * read after [parseLength] succeeds.
 */
object WifiTransferFraming {
    const val LENGTH_PREFIX_BYTES: Int = 4
    const val MAX_FRAME_BYTES: Int = 8 * 1024 * 1024

    fun encodeLength(length: Int): ByteArray {
        require(isValidFrameLength(length)) {
            "Frame length $length out of range 1..$MAX_FRAME_BYTES"
        }
        return byteArrayOf(
            (length ushr 24).toByte(),
            (length ushr 16).toByte(),
            (length ushr 8).toByte(),
            length.toByte(),
        )
    }

    fun encodeFrame(body: ByteArray): ByteArray = encodeLength(body.size) + body

    fun parseLength(prefix: ByteArray): Int? {
        if (prefix.size < LENGTH_PREFIX_BYTES) return null
        val length =
            ((prefix[0].toInt() and 0xFF) shl 24) or
                ((prefix[1].toInt() and 0xFF) shl 16) or
                ((prefix[2].toInt() and 0xFF) shl 8) or
                (prefix[3].toInt() and 0xFF)
        return length.takeIf { isValidFrameLength(it) }
    }

    fun isValidFrameLength(length: Int): Boolean =
        length in 1..MAX_FRAME_BYTES
}
