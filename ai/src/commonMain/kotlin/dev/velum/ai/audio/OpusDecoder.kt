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
)

interface OpusDecoder {
    fun decodeToPcm(oggBytes: ByteArray): PcmAudio

    /**
     * Demux + decode Opus packets, appending 16-bit LE PCM to [dest] in small frames
     * so multi-hour recordings never allocate a single heap buffer for the full PCM.
     */
    fun decodeToPcmFile(oggBytes: ByteArray, dest: ModelStoreDir): SpilledPcmMeta

    /**
     * Decode an on-disk Ogg Opus file without loading the whole file into a ByteArray.
     *
     * Common default streams Ogg pages via [ModelStoreDir.readRange] + [OggOpusDemuxer.demuxAtForEach]
     * (used by iOS) so the compressed file is never fully materialized. Android/JVM override with
     * RandomAccessFile + kopus packet decode.
     */
    fun decodePathToPcmFile(path: String, dest: ModelStoreDir): SpilledPcmMeta {
        val src = ModelStoreDir(path)
        val size = src.length()
        OggOpusDemuxer.demuxAtForEach(
            size = size,
            readAt = { buf, position, length ->
                readAtModelStore(src, size, buf, position, length)
            },
            onPacket = {
                // Discard — no packet list. Platforms with Opus decode override this method.
            },
        )
        throw UnsupportedOperationException(
            "Opus decode from path requires a platform override with a native decoder " +
                "(Android/JVM). Ogg demux streamed without loading the whole file.",
        )
    }
}

/**
 * Copy [length] bytes from [src] at [position] into [dest][0] for [OggOpusDemuxer.demuxAtForEach].
 * Returns bytes copied, or -1 at EOF. Never reads more than [length] (page-sized) bytes.
 */
internal fun readAtModelStore(
    src: ModelStoreDir,
    size: Long,
    dest: ByteArray,
    position: Long,
    length: Int,
): Int {
    if (position >= size || length <= 0) return -1
    val n = minOf(length.toLong(), size - position).toInt()
    if (n <= 0) return -1
    val chunk = src.readRange(position, n)
    if (chunk.isEmpty()) return -1
    chunk.copyInto(destination = dest, endIndex = chunk.size)
    return chunk.size
}

expect fun createOpusDecoder(): OpusDecoder

/** Stream metadata from an Ogg Opus bitstream (no packet list). */
data class OpusStreamMeta(
    val sampleRateHz: Int,
    val channels: Int,
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
    )

    fun demux(oggBytes: ByteArray): OpusStream {
        val packets = ArrayList<ByteArray>()
        val meta = demuxForEach(oggBytes) { packets.add(it) }
        return OpusStream(
            sampleRateHz = meta.sampleRateHz,
            channels = meta.channels,
            packets = packets,
        )
    }

    /**
     * Walk Ogg pages in [oggBytes], invoking [onHeader] when OpusHead is seen and
     * [onPacket] for each complete audio packet — without retaining the packet list.
     */
    fun demuxForEach(
        oggBytes: ByteArray,
        onHeader: (sampleRateHz: Int, channels: Int) -> Unit = { _, _ -> },
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
        onHeader: (sampleRateHz: Int, channels: Int) -> Unit = { _, _ -> },
        onPacket: (ByteArray) -> Unit,
    ): OpusStreamMeta {
        require(size >= 27L) { "Ogg buffer too small" }
        var offset = 0L
        var sampleRate = 48_000
        var channels = 1
        var sawIdHeader = false
        val packetParts = ArrayList<ByteArray>()
        val header = ByteArray(27)

        while (offset + 27L <= size) {
            require(readAt(header, offset, 27) == 27) { "Truncated Ogg page header" }
            if (header[0] != 'O'.code.toByte() ||
                header[1] != 'g'.code.toByte() ||
                header[2] != 'g'.code.toByte() ||
                header[3] != 'S'.code.toByte()
            ) {
                throw IllegalArgumentException("Invalid Ogg page magic at offset $offset")
            }
            val pageSegments = header[26].toInt() and 0xff
            val segmentTable = ByteArray(pageSegments)
            require(readAt(segmentTable, offset + 27L, pageSegments) == pageSegments) {
                "Truncated Ogg segment table"
            }
            var bodySize = 0
            for (i in 0 until pageSegments) {
                bodySize += segmentTable[i].toInt() and 0xff
            }
            val bodyStart = offset + 27L + pageSegments
            val bodyEnd = bodyStart + bodySize
            require(bodyEnd <= size) { "Truncated Ogg page body" }
            val body = ByteArray(bodySize)
            if (bodySize > 0) {
                require(readAt(body, bodyStart, bodySize) == bodySize) { "Truncated Ogg page body" }
            }

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
                        channels = packetBuf[9].toInt() and 0xff
                        sampleRate = readLe32(packetBuf, 12)
                        sawIdHeader = true
                        onHeader(sampleRate, channels)
                    } else if (sawIdHeader && !startsWithOpusTags(packetBuf)) {
                        onPacket(packetBuf)
                    }
                }
            }
            offset = bodyEnd
        }

        // EOS with a continued packet (segSize==255 only) — emit or fail loudly.
        if (packetParts.isNotEmpty()) {
            require(sawIdHeader) { "Trailing Ogg packet without OpusHead" }
            val packetBuf = concatPackets(packetParts)
            packetParts.clear()
            if (!startsWithOpusTags(packetBuf)) {
                onPacket(packetBuf)
            }
        }

        require(sawIdHeader) { "No OpusHead page found in Ogg stream" }
        return OpusStreamMeta(sampleRateHz = sampleRate, channels = channels)
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
