package dev.velum.ai.audio

import eu.buney.kopus.OpusApplication
import eu.buney.kopus.OpusEncoder
import java.io.OutputStream
import kotlin.math.PI
import kotlin.math.sin

/**
 * Tiny synthetic Ogg Opus builders for #86 acceptance tests.
 *
 * How fixtures are made (no Plaud / copyrighted audio):
 * 1. Encode PCM with kopus [OpusEncoder] (48 kHz mono, Audio application).
 * 2. Wrap OpusHead + OpusTags + audio packets in minimal Ogg pages (RFC 3533 / 7845).
 *    CRC and granule positions are left zero — [OggOpusDemuxer] does not require them.
 *
 * Silence packet for zero PCM @ 20 ms / 48 kHz is deterministic (`f8 ff fe`).
 */
object SyntheticOggOpus {
    const val SAMPLE_RATE_HZ: Int = 48_000
    const val CHANNELS: Int = 1
    /** 20 ms frame at 48 kHz. */
    const val FRAME_SAMPLES: Int = SAMPLE_RATE_HZ / 50

    /** Encode one 20 ms mono frame; returns the Opus packet bytes. */
    fun encodePcmFrame(pcm: ShortArray): ByteArray {
        require(pcm.size == FRAME_SAMPLES) { "expected $FRAME_SAMPLES samples, got ${pcm.size}" }
        OpusEncoder(SAMPLE_RATE_HZ, CHANNELS, OpusApplication.Audio).use { enc ->
            val out = ByteArray(4_000)
            val n = enc.encode(pcm, 0, FRAME_SAMPLES, out, 0, out.size)
            require(n > 0) { "Opus encode returned $n" }
            return out.copyOf(n)
        }
    }

    /** Deterministic silence packet (zero PCM → `f8fffe`). */
    fun silencePacket(): ByteArray = encodePcmFrame(ShortArray(FRAME_SAMPLES))

    /** Short tone packet for golden equality (not silence). */
    fun tonePacket(freqHz: Double = 440.0, amplitude: Double = 0.25): ByteArray {
        val pcm = ShortArray(FRAME_SAMPLES) { i ->
            val s = sin(2.0 * PI * freqHz * i / SAMPLE_RATE_HZ) * amplitude * Short.MAX_VALUE
            s.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return encodePcmFrame(pcm)
    }

    /**
     * Build a small in-memory Ogg Opus bitstream from [packets]
     * (OpusHead + OpusTags + one page per audio packet).
     */
    fun buildOgg(packets: List<ByteArray>): ByteArray {
        require(packets.isNotEmpty()) { "need at least one audio packet" }
        val parts = ArrayList<ByteArray>(packets.size + 2)
        parts += buildOggPage(opusHead())
        parts += buildOggPage(opusTags())
        for (p in packets) {
            require(p.size in 1..254) { "packet size ${p.size} must fit one Ogg segment" }
            parts += buildOggPage(p)
        }
        val total = parts.sumOf { it.size }
        val out = ByteArray(total)
        var o = 0
        for (p in parts) {
            p.copyInto(out, o)
            o += p.size
        }
        return out
    }

    /**
     * Stream a synthetic Ogg Opus of [packetCount] silence packets onto [out]
     * without retaining the full file on the heap. Packs up to [packetsPerPage]
     * packets per Ogg page for I/O efficiency.
     */
    fun writeSilenceOgg(
        out: OutputStream,
        packetCount: Long,
        packetsPerPage: Int = 255,
    ): Long {
        require(packetCount > 0)
        require(packetsPerPage in 1..255)
        val packet = silencePacket()
        require(packet.size in 1..254)
        var written = 0L
        fun writePage(page: ByteArray) {
            out.write(page)
            written += page.size.toLong()
        }
        writePage(buildOggPage(opusHead()))
        writePage(buildOggPage(opusTags()))
        var remaining = packetCount
        val batch = ArrayList<ByteArray>(packetsPerPage)
        while (remaining > 0) {
            batch.clear()
            val n = minOf(packetsPerPage.toLong(), remaining).toInt()
            repeat(n) { batch += packet }
            writePage(buildOggPageMulti(batch))
            remaining -= n.toLong()
        }
        out.flush()
        return written
    }

    fun opusHead(
        sampleRateHz: Int = SAMPLE_RATE_HZ,
        channels: Int = CHANNELS,
    ): ByteArray {
        val head = ByteArray(19)
        head[0] = 'O'.code.toByte()
        head[1] = 'p'.code.toByte()
        head[2] = 'u'.code.toByte()
        head[3] = 's'.code.toByte()
        head[4] = 'H'.code.toByte()
        head[5] = 'e'.code.toByte()
        head[6] = 'a'.code.toByte()
        head[7] = 'd'.code.toByte()
        head[8] = 1 // version
        head[9] = channels.toByte()
        // pre-skip = 0
        head[10] = 0
        head[11] = 0
        writeIntLe(head, 12, sampleRateHz)
        // output gain + channel mapping family
        head[16] = 0
        head[17] = 0
        head[18] = 0
        return head
    }

    fun opusTags(): ByteArray {
        // OpusTags + vendor length 0 + comment count 0
        return byteArrayOf(
            'O'.code.toByte(), 'p'.code.toByte(), 'u'.code.toByte(), 's'.code.toByte(),
            'T'.code.toByte(), 'a'.code.toByte(), 'g'.code.toByte(), 's'.code.toByte(),
            0, 0, 0, 0,
            0, 0, 0, 0,
        )
    }

    fun buildOggPage(packet: ByteArray): ByteArray =
        buildOggPageMulti(listOf(packet))

    fun buildOggPageMulti(packets: List<ByteArray>): ByteArray {
        require(packets.isNotEmpty())
        for (p in packets) {
            require(p.size in 1..254) { "segment size ${p.size}" }
        }
        val bodySize = packets.sumOf { it.size }
        val header = ByteArray(27 + packets.size + bodySize)
        header[0] = 'O'.code.toByte()
        header[1] = 'g'.code.toByte()
        header[2] = 'g'.code.toByte()
        header[3] = 'S'.code.toByte()
        header[4] = 0 // version
        header[5] = 0 // header type
        // granule / serial / seq / crc left zero — demuxer ignores them
        header[26] = packets.size.toByte()
        var o = 27
        for (p in packets) {
            header[o++] = p.size.toByte()
        }
        for (p in packets) {
            p.copyInto(header, o)
            o += p.size
        }
        return header
    }

    private fun writeIntLe(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xff).toByte()
        target[offset + 1] = ((value ushr 8) and 0xff).toByte()
        target[offset + 2] = ((value ushr 16) and 0xff).toByte()
        target[offset + 3] = ((value ushr 24) and 0xff).toByte()
    }
}
