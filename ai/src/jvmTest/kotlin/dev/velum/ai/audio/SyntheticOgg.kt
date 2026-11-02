package dev.velum.ai.audio

/** Deterministic synthetic Ogg Opus pieces shared by tests (no real audio). */
object SyntheticOgg {
    fun opusHead(channels: Int, rate: Int, version: Int = 1, family: Int = 0): ByteArray {
        val h = ByteArray(19)
        "OpusHead".encodeToByteArray().copyInto(h)
        h[8] = version.toByte()
        h[9] = channels.toByte()
        h[18] = family.toByte()
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

    fun page(packets: List<ByteArray>, seq: Int, flags: Int = 0): ByteArray {
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
        out[18] = seq.toByte()
        out[26] = table.size.toByte()
        table.forEachIndexed { i, b -> out[27 + i] = b }
        body.copyInto(out, 27 + table.size)
        return out
    }

    fun tags(): ByteArray = "OpusTags".encodeToByteArray() + ByteArray(8)
}
