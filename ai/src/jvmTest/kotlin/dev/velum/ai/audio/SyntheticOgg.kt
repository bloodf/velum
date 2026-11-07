package dev.velum.ai.audio

/** Deterministic synthetic Ogg Opus pieces shared by tests (no real audio). */
object SyntheticOgg {
    fun opusHead(channels: Int, rate: Int, version: Int = 1, family: Int = 0, mapping: ByteArray? = null, preSkip: Int = 0): ByteArray {
        val h = ByteArray(19 + (mapping?.size ?: 0))
        mapping?.copyInto(h, 19)
        "OpusHead".encodeToByteArray().copyInto(h)
        h[8] = version.toByte()
        h[9] = channels.toByte()
        h[18] = family.toByte()
        h[10] = (preSkip and 0xff).toByte()
        h[11] = ((preSkip shr 8) and 0xff).toByte()
        h[12] = (rate and 0xff).toByte()
        h[13] = ((rate shr 8) and 0xff).toByte()
        h[14] = ((rate shr 16) and 0xff).toByte()
        h[15] = ((rate shr 24) and 0xff).toByte()
        return h
    }

    /** Real 20 ms Opus packet from a deterministic 440 Hz tone (kopus encoder). */
    fun audioPacket(stereo: Boolean): ByteArray {
        val ch = if (stereo) 2 else 1
        val pcm = ShortArray(960 * ch) { (kotlin.math.sin(2 * Math.PI * 440 * (it / ch) / 48_000) * 8000).toInt().toShort() }
        val buf = ByteArray(1275)
        eu.buney.kopus.OpusEncoder(48_000, ch, eu.buney.kopus.OpusApplication.Audio).use { enc ->
            val n = enc.encode(pcm, 0, 960, buf, 0, buf.size)
            return buf.copyOf(n)
        }
    }

    fun page(packets: List<ByteArray>, seq: Int, flags: Int = 0, granule: Long = 0): ByteArray {
        val table = ArrayList<Byte>()
        for (p in packets) {
            var n = p.size
            while (n >= 255) { table.add(255.toByte()); n -= 255 }
            table.add(n.toByte())
        }
        val body = packets.fold(ByteArray(0)) { a, b -> a + b }
        val out = ByteArray(27 + table.size + body.size)
        "OggS".encodeToByteArray().copyInto(out)
        out[5] = flags.toByte()
        for (i in 0 until 8) out[6 + i] = (granule shr (8 * i)).toByte()
        out[18] = seq.toByte()
        out[26] = table.size.toByte()
        table.forEachIndexed { i, b -> out[27 + i] = b }
        body.copyInto(out, 27 + table.size)
        val crc = crc(out)
        for (i in 0 until 4) out[22 + i] = (crc shr (8 * i)).toByte()
        return out
    }

    /** Ogg page CRC-32 (poly 0x04C11DB7, MSB first, init 0) over the page with its CRC field zeroed. */
    fun crc(page: ByteArray): Int {
        var c = 0
        for (b in page) {
            c = c xor ((b.toInt() and 0xff) shl 24)
            repeat(8) { c = if (c < 0) (c shl 1) xor 0x04C11DB7 else c shl 1 }
        }
        return c
    }

    fun tags(): ByteArray = "OpusTags".encodeToByteArray() + ByteArray(8)
}
