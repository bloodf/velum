package dev.velum.ai.audio

import dev.velum.ai.local.ModelStoreDir

/**
 * Decode Ogg Opus (Plaud Note Pro recordings) to 16-bit PCM.
 * Common code demuxes Ogg; platform code decodes Opus packets.
 */
data class PcmAudio(
    val samples: ShortArray,
    val sampleRateHz: Int,
    val channels: Int,
    /** True when the Ogg stream ended inside a page or packet; audio before the cut is kept. */
    val truncated: Boolean = false,
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

/** Metadata for PCM written directly to a spill file (no full-heap ShortArray). */
data class SpilledPcmMeta(
    val sampleRateHz: Int,
    val channels: Int,
    val sizeBytes: Long,
    /** True when the Ogg stream ended inside a page or packet; audio before the cut is kept. */
    val truncated: Boolean = false,
)

/**
 * Decoded Opus output contract (#95): 16 kHz mono PCM16 LE, the same as the import path
 * (AudioNormalizer). OpusHead rate/channels are metadata only and never change the output.
 */
const val DECODED_PCM_RATE_HZ = 16_000
const val DECODED_PCM_CHANNELS = 1

interface OpusDecoder {
    fun decodeToPcm(oggBytes: ByteArray): PcmAudio

    /**
     * Demux + decode Opus packets, appending 16-bit LE PCM to [dest] in small frames
     * so multi-hour recordings never allocate a single heap buffer for the full PCM.
     */
    fun decodeToPcmFile(oggBytes: ByteArray, dest: ModelStoreDir): SpilledPcmMeta

    /**
     * Decode an on-disk Ogg Opus file without loading the whole file into a ByteArray.
     * Default reads all bytes then [decodeToPcmFile]; Android/JVM stream pages from disk.
     * [checkCancelled] runs once per packet and should throw to abort the decode.
     */
    fun decodePathToPcmFile(path: String, dest: ModelStoreDir, checkCancelled: () -> Unit = {}): SpilledPcmMeta =
        decodeToPcmFile(ModelStoreDir(path).readBytes(), dest)
}

expect fun createOpusDecoder(): OpusDecoder

/** Stream metadata from an Ogg Opus bitstream (no packet list). */
data class OpusStreamMeta(
    val sampleRateHz: Int,
    val channels: Int,
    /**
     * True when the stream ended inside a page header, segment table, page body or an open
     * continued packet. Complete pages before the cut were still delivered. Callers must surface this.
     */
    val truncated: Boolean = false,
)

/**
 * Pure-Kotlin Ogg demux for Opus bitstreams (RFC 3533 / RFC 7845).
 * Prefer [demuxForEach] so multi-hour files do not hold every Opus packet on the heap.
 */
object OggOpusDemuxer {
    data class OpusStream(
        val sampleRateHz: Int,
        val channels: Int,
        val packets: List<ByteArray>,
        val truncated: Boolean = false,
    )

    fun demux(oggBytes: ByteArray): OpusStream {
        val packets = ArrayList<ByteArray>()
        val meta = demuxForEach(oggBytes) { packets.add(it) }
        return OpusStream(
            sampleRateHz = meta.sampleRateHz,
            channels = meta.channels,
            packets = packets,
            truncated = meta.truncated,
        )
    }

    /**
     * Walk Ogg pages in [oggBytes], invoking [onHeader] when OpusHead is seen and
     * [onPacket] for each complete audio packet — without retaining the packet list.
     */
    fun demuxForEach(
        oggBytes: ByteArray,
        onHeader: (sampleRateHz: Int, channels: Int, preSkip: Int) -> Unit = { _, _, _ -> },
        onPacket: (ByteArray) -> Unit,
    ): OpusStreamMeta {
        require(oggBytes.size >= 27) { "Ogg buffer too small" }
        return demuxAtForEach(
            size = oggBytes.size.toLong(),
            readAt = { dest, position, length ->
                val pos = position.toInt()
                if (pos + length > oggBytes.size) return@demuxAtForEach -1
                oggBytes.copyInto(
                    destination = dest,
                    destinationOffset = 0,
                    startIndex = pos,
                    endIndex = pos + length,
                )
                length
            },
            onHeader = onHeader,
            onPacket = onPacket,
        )
    }

    /**
     * Stream Ogg pages from a random-access reader (file). [size] is the total byte
     * length; [readAt] copies [length] bytes starting at [position] into [dest][0].
     */
    fun demuxAtForEach(
        size: Long,
        readAt: (dest: ByteArray, position: Long, length: Int) -> Int,
        onHeader: (sampleRateHz: Int, channels: Int, preSkip: Int) -> Unit = { _, _, _ -> },
        onPacket: (ByteArray) -> Unit,
    ): OpusStreamMeta {
        require(size >= 27L) { "Ogg buffer too small" }
        var offset = 0L
        var sampleRate = 48_000
        var channels = 1
        var sawIdHeader = false
        val packetParts = ArrayList<ByteArray>()
        val header = ByteArray(27)
        var truncated = false
        var audioPackets = 0

        while (offset + 27L <= size) {
            if (readAt(header, offset, 27) != 27) { truncated = true; break }
            if (header[0] != 'O'.code.toByte() ||
                header[1] != 'g'.code.toByte() ||
                header[2] != 'g'.code.toByte() ||
                header[3] != 'S'.code.toByte()
            ) {
                throw IllegalArgumentException("Invalid Ogg page magic at offset $offset")
            }
            val pageSegments = header[26].toInt() and 0xff
            val segmentTable = ByteArray(pageSegments)
            if (readAt(segmentTable, offset + 27L, pageSegments) != pageSegments) { truncated = true; break }
            var bodySize = 0
            for (i in 0 until pageSegments) {
                bodySize += segmentTable[i].toInt() and 0xff
            }
            val bodyStart = offset + 27L + pageSegments
            val bodyEnd = bodyStart + bodySize
            if (bodyEnd > size) { truncated = true; break }
            val body = ByteArray(bodySize)
            if (bodySize > 0 && readAt(body, bodyStart, bodySize) != bodySize) { truncated = true; break }

            var bodyPos = 0
            for (i in 0 until pageSegments) {
                val segSize = segmentTable[i].toInt() and 0xff
                val seg = body.copyOfRange(bodyPos, bodyPos + segSize)
                bodyPos += segSize
                packetParts.add(seg)
                if (segSize < 255) {
                    val packetBuf = concatPackets(packetParts)
                    packetParts.clear()
                    if (!sawIdHeader && packetBuf.size >= 19 && startsWithOpusHead(packetBuf)) {
                        // RFC 7845 section 5.1: major version nibble must be 0; channel count 0 is invalid.
                        require(packetBuf[8].toInt() and 0xf0 == 0) { "Unsupported OpusHead version" }
                        channels = packetBuf[9].toInt() and 0xff
                        require(channels > 0) { "OpusHead channel count is zero" }
                        // RFC 7845 section 5.1.1: single stream = family 0, or family 1 with one stream
                        // (stream count byte 19). Anything else is multistream and needs a multistream
                        // decoder, so fail loudly instead of decoding garbage.
                        val family = packetBuf[18].toInt() and 0xff
                        val singleStream = channels <= 2 && when (family) {
                            0 -> true
                            1 -> packetBuf.size >= 21 + channels && (packetBuf[19].toInt() and 0xff) == 1
                            else -> false
                        }
                        require(singleStream) {
                            "Unsupported OpusHead mapping (family $family, $channels channels): multistream Opus"
                        }
                        sampleRate = readLe32(packetBuf, 12)
                        sawIdHeader = true
                        // RFC 7845: pre-skip is in 48 kHz samples; the first ones are encoder warm-up, not audio.
                        onHeader(sampleRate, channels, (packetBuf[10].toInt() and 0xff) or ((packetBuf[11].toInt() and 0xff) shl 8))
                    } else if (sawIdHeader && !startsWithOpusTags(packetBuf)) {
                        if (packetBuf.isNotEmpty()) audioPackets++
                        onPacket(packetBuf)
                    }
                }
            }
            offset = bodyEnd
        }
        // 1..26 bytes left (too short for a page header) are a truncated tail, not a clean end.
        if (offset < size) truncated = true

        if (truncated) {
            // The cut page's partial packet is dropped; complete pages before it are kept.
            packetParts.clear()
        } else if (packetParts.isNotEmpty()) {
            // Clean EOF with a still-continued packet (last segment was 255 bytes): emit it, but flag it.
            require(sawIdHeader) { "Trailing Ogg packet without OpusHead" }
            val packetBuf = concatPackets(packetParts)
            packetParts.clear()
            truncated = true
            if (!startsWithOpusTags(packetBuf)) {
                if (packetBuf.isNotEmpty()) audioPackets++
                onPacket(packetBuf)
            }
        }

        require(sawIdHeader) { "No OpusHead page found in Ogg stream" }
        // Salvage only when something decodable survived; otherwise fail loudly.
        require(!truncated || audioPackets > 0) { "Ogg stream truncated before any complete audio packet" }
        return OpusStreamMeta(sampleRateHz = sampleRate, channels = channels, truncated = truncated)
    }

    private fun concatPackets(parts: List<ByteArray>): ByteArray {
        val total = parts.sumOf { it.size }
        val out = ByteArray(total)
        var o = 0
        for (p in parts) {
            p.copyInto(out, o)
            o += p.size
        }
        return out
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
