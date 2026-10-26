package dev.velum.ai.audio

/**
 * Decode Ogg Opus (Plaud Note Pro recordings) to 16-bit PCM.
 * Common code demuxes Ogg; platform code decodes Opus packets.
 */
data class PcmAudio(
    val samples: ShortArray,
    val sampleRateHz: Int,
    val channels: Int,
) {
    fun toByteArray(): ByteArray {
        val out = ByteArray(samples.size * 2)
        var i = 0
        for (s in samples) {
            out[i++] = (s.toInt() and 0xff).toByte()
            out[i++] = ((s.toInt() shr 8) and 0xff).toByte()
        }
        return out
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PcmAudio) return false
        return sampleRateHz == other.sampleRateHz &&
            channels == other.channels &&
            samples.contentEquals(other.samples)
    }

    override fun hashCode(): Int {
        var result = samples.contentHashCode()
        result = 31 * result + sampleRateHz
        result = 31 * result + channels
        return result
    }
}

interface OpusDecoder {
    fun decodeToPcm(oggBytes: ByteArray): PcmAudio
}

expect fun createOpusDecoder(): OpusDecoder

/**
 * Pure-Kotlin Ogg demux for Opus bitstreams (RFC 3533 / RFC 7845).
 * Returns raw Opus packets (no TOC rewrite) plus stream metadata from the ID header.
 */
object OggOpusDemuxer {
    data class OpusStream(
        val sampleRateHz: Int,
        val channels: Int,
        val packets: List<ByteArray>,
    )

    fun demux(oggBytes: ByteArray): OpusStream {
        require(oggBytes.size >= 27) { "Ogg buffer too small" }
        val packets = ArrayList<ByteArray>()
        var offset = 0
        var sampleRate = 48_000
        var channels = 1
        var sawIdHeader = false

        while (offset + 27 <= oggBytes.size) {
            if (oggBytes[offset] != 'O'.code.toByte() ||
                oggBytes[offset + 1] != 'g'.code.toByte() ||
                oggBytes[offset + 2] != 'g'.code.toByte() ||
                oggBytes[offset + 3] != 'S'.code.toByte()
            ) {
                throw IllegalArgumentException("Invalid Ogg page magic at offset $offset")
            }
            val pageSegments = oggBytes[offset + 26].toInt() and 0xff
            val segmentTableStart = offset + 27
            val segmentTableEnd = segmentTableStart + pageSegments
            require(segmentTableEnd <= oggBytes.size) { "Truncated Ogg segment table" }

            var bodySize = 0
            for (i in 0 until pageSegments) {
                bodySize += oggBytes[segmentTableStart + i].toInt() and 0xff
            }
            val bodyStart = segmentTableEnd
            val bodyEnd = bodyStart + bodySize
            require(bodyEnd <= oggBytes.size) { "Truncated Ogg page body" }

            var bodyPos = bodyStart
            var packetBuf = ByteArray(0)
            for (i in 0 until pageSegments) {
                val segSize = oggBytes[segmentTableStart + i].toInt() and 0xff
                val seg = oggBytes.copyOfRange(bodyPos, bodyPos + segSize)
                bodyPos += segSize
                packetBuf += seg
                if (segSize < 255) {
                    if (!sawIdHeader && packetBuf.size >= 19 && startsWithOpusHead(packetBuf)) {
                        channels = packetBuf[9].toInt() and 0xff
                        sampleRate = readLe32(packetBuf, 12)
                        sawIdHeader = true
                    } else if (sawIdHeader && !startsWithOpusTags(packetBuf)) {
                        packets.add(packetBuf)
                    }
                    packetBuf = ByteArray(0)
                }
            }
            offset = bodyEnd
        }

        require(sawIdHeader) { "No OpusHead page found in Ogg stream" }
        return OpusStream(sampleRateHz = sampleRate, channels = channels, packets = packets)
    }

    private fun startsWithOpusHead(packet: ByteArray): Boolean =
        packet.size >= 8 &&
            packet[0] == 'O'.code.toByte() &&
            packet[1] == 'p'.code.toByte() &&
            packet[2] == 'u'.code.toByte() &&
            packet[3] == 's'.code.toByte() &&
            packet[4] == 'H'.code.toByte() &&
            packet[5] == 'e'.code.toByte() &&
            packet[6] == 'a'.code.toByte() &&
            packet[7] == 'd'.code.toByte()

    private fun startsWithOpusTags(packet: ByteArray): Boolean =
        packet.size >= 8 &&
            packet[0] == 'O'.code.toByte() &&
            packet[1] == 'p'.code.toByte() &&
            packet[2] == 'u'.code.toByte() &&
            packet[3] == 's'.code.toByte() &&
            packet[4] == 'T'.code.toByte() &&
            packet[5] == 'a'.code.toByte() &&
            packet[6] == 'g'.code.toByte() &&
            packet[7] == 's'.code.toByte()

    private fun readLe32(bytes: ByteArray, index: Int): Int {
        val b0 = bytes[index].toInt() and 0xff
        val b1 = bytes[index + 1].toInt() and 0xff
        val b2 = bytes[index + 2].toInt() and 0xff
        val b3 = bytes[index + 3].toInt() and 0xff
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }
}
