package dev.velum.ai.audio

import dev.velum.ai.local.ModelStoreDir
import dev.velum.ble.crypto.JcaFileDecryptor
import dev.velum.ble.crypto.rsaKeyPairToPem
import dev.velum.protocol.PlaudFile
import dev.velum.protocol.PlaudFileCrypto
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #95: synthetic-only Ogg Opus + PLAUD.AI fixtures, generated deterministically here.
 * No owner audio, keys or identity values. Proves software correctness only; says nothing
 * about the real Note Pro rate/channels (#7).
 */
class OggOpusPlaudSyntheticTest {
    private val decoder = KopusOpusDecoder()

    // ---- fixture builders -------------------------------------------------

    private fun opusHead(channels: Int, rate: Int, version: Int = 1): ByteArray {
        val h = ByteArray(19)
        "OpusHead".encodeToByteArray().copyInto(h)
        h[8] = version.toByte()
        h[9] = channels.toByte()
        h[12] = (rate and 0xff).toByte()
        h[13] = ((rate shr 8) and 0xff).toByte()
        h[14] = ((rate shr 16) and 0xff).toByte()
        h[15] = ((rate shr 24) and 0xff).toByte()
        return h
    }

    /** Real 20 ms Opus packet from a deterministic synthetic 440 Hz tone (kopus encoder). */
    private fun audioPacket(stereo: Boolean): ByteArray {
        val ch = if (stereo) 2 else 1
        val pcm = ShortArray(960 * ch) { (kotlin.math.sin(2 * Math.PI * 440 * (it / ch) / 48_000) * 8000).toInt().toShort() }
        val buf = ByteArray(1275)
        eu.buney.kopus.OpusEncoder(48_000, ch, eu.buney.kopus.OpusApplication.Audio).use { enc ->
            val n = enc.encode(pcm, 0, 960, buf, 0, buf.size)
            return buf.copyOf(n)
        }
    }

    private fun page(packets: List<ByteArray>, seq: Int, flags: Int = 0): ByteArray {
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

    private fun ogg(channels: Int, rate: Int, nPackets: Int, head: ByteArray = opusHead(channels, rate)): ByteArray {
        val tags = "OpusTags".encodeToByteArray() + ByteArray(8)
        val audio = List(nPackets) { audioPacket(channels == 2) }
        return page(listOf(head), 0, 2) + page(listOf(tags), 1) + page(audio, 2, 4)
    }

    private fun decode(bytes: ByteArray): SpilledPcmMeta {
        val dir = Files.createTempFile("velum-ogg", ".pcm")
        try {
            return decoder.decodeToPcmFile(bytes, ModelStoreDir(dir.toString()))
        } finally {
            Files.deleteIfExists(dir)
        }
    }

    // ---- header metadata vs normalized output ----------------------------

    @Test
    fun header_metadata_is_reported_verbatim_for_all_channel_rate_combos() {
        for (ch in 1..2) for (rate in listOf(8_000, 12_000, 16_000, 24_000, 44_100, 48_000)) {
            val s = OggOpusDemuxer.demux(ogg(ch, rate, 2))
            assertEquals(rate, s.sampleRateHz, "header rate ch=$ch")
            assertEquals(ch, s.channels)
            assertEquals(2, s.packets.size)
        }
    }

    @Test
    fun decode_supported_rates_and_channels_yield_expected_duration() {
        for (ch in 1..2) for (rate in listOf(8_000, 12_000, 16_000, 24_000, 48_000)) {
            val n = 5
            val meta = decode(ogg(ch, rate, n))
            assertEquals(rate, meta.sampleRateHz)
            assertEquals(ch, meta.channels)
            // 5 x 20 ms frames, 16-bit samples
            assertEquals(n * (rate / 50) * ch * 2L, meta.sizeBytes, "rate=$rate ch=$ch")
        }
    }

    @Test
    fun unsupported_header_rate_normalizes_to_48k_decoder_rate() {
        // OpusHead rate is informational (RFC 7845); 44100 is not a decoder rate.
        val meta = decode(ogg(1, 44_100, 3))
        assertEquals(48_000, meta.sampleRateHz)
        assertEquals(3 * 960 * 2L, meta.sizeBytes)
    }

    @Test
    fun decoded_pcm_is_not_plc_silence() {
        // Regression: kopus decode() defaults inLen=0, which made every packet a PLC (silent) frame.
        val pcm = decoder.decodeToPcm(ogg(1, 48_000, 5))
        assertTrue(pcm.samples.any { it.toInt() != 0 }, "tone must survive decode")
        assertTrue(pcm.samples.map { kotlin.math.abs(it.toInt()) }.max() > 1000)
    }

    @Test
    fun decodeToPcm_in_memory_matches_spilled_output() {
        val bytes = ogg(2, 16_000, 4)
        val pcm = decoder.decodeToPcm(bytes)
        assertEquals(16_000, pcm.sampleRateHz)
        assertEquals(2, pcm.channels)
        assertEquals(4 * 320 * 2, pcm.samples.size)
    }

    // ---- malformed / truncated -------------------------------------------

    @Test
    fun malformed_opus_head_is_rejected() {
        // too short to be an ID header -> no header seen
        assertFailsWith<IllegalArgumentException> {
            OggOpusDemuxer.demux(page(listOf(opusHead(1, 48_000).copyOf(12)), 0, 2))
        }
        // wrong magic
        val bad = opusHead(1, 48_000).also { it[0] = 'X'.code.toByte() }
        assertFailsWith<IllegalArgumentException> { OggOpusDemuxer.demux(page(listOf(bad), 0, 2)) }
        // audio before any head
        assertFailsWith<IllegalArgumentException> {
            OggOpusDemuxer.demux(page(listOf(audioPacket(false)), 0) + page(listOf(audioPacket(false)), 1))
        }
    }

    @Test
    fun invalid_channel_count_and_version_are_rejected() {
        for (ch in listOf(0)) {
            assertFailsWith<IllegalArgumentException>("channels=$ch") {
                OggOpusDemuxer.demux(ogg(ch, 48_000, 1))
            }
        }
        assertFailsWith<IllegalArgumentException>("version 0x10") {
            OggOpusDemuxer.demux(ogg(1, 48_000, 1, opusHead(1, 48_000, version = 0x10)))
        }
    }

    @Test
    fun opus_head_major_version_zero_is_accepted_and_nibble_boundary_is_exact() {
        // RFC 7845: low nibble is the minor version (any), high nibble must be 0.
        for (v in listOf(0x00, 0x01, 0x0F)) {
            val s = OggOpusDemuxer.demux(ogg(2, 48_000, 1, opusHead(2, 48_000, version = v)))
            assertEquals(2, s.channels, "version=0x${v.toString(16)}")
            assertFalse(s.truncated)
        }
        assertFailsWith<IllegalArgumentException> {
            OggOpusDemuxer.demux(ogg(1, 48_000, 1, opusHead(1, 48_000, version = 0x10)))
        }
    }

    @Test
    fun truncated_tail_after_complete_audio_page_is_salvaged_and_flagged() {
        val head = page(listOf(opusHead(1, 48_000)), 0, 2) + page(listOf("OpusTags".encodeToByteArray() + ByteArray(8)), 1)
        val a = page(List(2) { audioPacket(false) }, 2)
        val b = page(List(2) { audioPacket(false) }, 3, 4)
        val full = head + a + b
        // 1..26 byte stub, mid-header, mid-segment-table and mid-body cuts of the last page.
        val cuts = (head.size + a.size + 1) until full.size
        for (cut in cuts) {
            val s = OggOpusDemuxer.demux(full.copyOf(cut))
            assertEquals(2, s.packets.size, "cut=$cut keeps the complete first audio page")
            assertTrue(s.truncated, "cut=$cut must be flagged, never silent")
        }
        val clean = OggOpusDemuxer.demux(full)
        assertEquals(4, clean.packets.size)
        assertFalse(clean.truncated)
        // 5 trailing bytes after a complete page.
        val tail = OggOpusDemuxer.demux(head + a + ByteArray(5))
        assertEquals(2, tail.packets.size)
        assertTrue(tail.truncated)
    }

    @Test
    fun decoder_reports_truncation_and_still_decodes_complete_pages() {
        val head = page(listOf(opusHead(1, 48_000)), 0, 2) + page(listOf("OpusTags".encodeToByteArray() + ByteArray(8)), 1)
        val a = page(List(2) { audioPacket(false) }, 2)
        val meta = decode(head + a + ByteArray(7))
        assertTrue(meta.truncated)
        assertEquals(2 * 960 * 2L, meta.sizeBytes)
        assertFalse(decode(head + a).truncated)
    }

    @Test
    fun truncation_before_any_complete_audio_packet_fails_loudly() {
        val full = ogg(1, 48_000, 3)
        // the only audio page is cut: nothing decodable survived, so it must throw
        val p2start = full.size - page(List(3) { audioPacket(false) }, 2, 4).size
        for (cut in (p2start + 1) until full.size) {
            assertFailsWith<IllegalArgumentException>("cut=$cut") {
                OggOpusDemuxer.demux(full.copyOf(cut))
            }
        }
        // cut inside the head page
        assertFailsWith<IllegalArgumentException> { OggOpusDemuxer.demux(full.copyOf(30)) }
    }

    @Test
    fun trailing_garbage_after_valid_pages_is_rejected() {
        val full = ogg(1, 48_000, 1) + ByteArray(30) { 0x55 }
        assertFailsWith<IllegalArgumentException> { OggOpusDemuxer.demux(full) }
    }

    // ---- PLAUD.AI decrypt -------------------------------------------------

    private val keys = rsaKeyPairToPem()
    private val dec = JcaFileDecryptor()

    @Test
    fun plaud_round_trip_decrypts_to_exact_ogg_and_decodes() {
        val ogg = ogg(2, 48_000, 6)
        val file = dec.encryptFile(keys.first, ogg)
        assertContentEquals("PLAUD.AI".encodeToByteArray(), file.copyOf(8))
        assertFalse(file.copyOfRange(PlaudFile.HEADER_SIZE, file.size).contentEquals(ogg), "body is encrypted")
        val plain = PlaudFileCrypto.decrypt(file, keys.second, dec)
        assertContentEquals(ogg, plain)
        val meta = decode(plain)
        assertEquals(2, meta.channels)
        assertEquals(6 * 960 * 2 * 2L, meta.sizeBytes)
    }

    @Test
    fun plaud_wrong_key_never_yields_valid_ogg_and_does_not_leak_key() {
        val ogg = ogg(1, 48_000, 2)
        val file = dec.encryptFile(keys.first, ogg)
        val other = rsaKeyPairToPem()
        val err = runCatching { PlaudFileCrypto.decrypt(file, other.second, dec) }
        err.exceptionOrNull()?.let { e ->
            val msg = e.toString()
            assertFalse(msg.contains("PRIVATE KEY") || msg.contains(other.second.lines()[1]), "no key material in error")
        }
        err.getOrNull()?.let { assertFailsWith<IllegalArgumentException> { OggOpusDemuxer.demux(it) } }
    }

    @Test
    fun plaud_wrong_content_key_gives_garbage_not_ogg() {
        // Same RSA key, but body encrypted with a different content key than the wrapped one.
        val ogg = ogg(1, 48_000, 2)
        val good = dec.encryptFile(keys.first, ogg, contentKey = ByteArray(32) { 1 })
        val other = dec.encryptFile(keys.first, ogg, contentKey = ByteArray(32) { 2 })
        val mixed = good.copyOf().also {
            other.copyOfRange(PlaudFile.HEADER_SIZE, other.size).copyInto(it, PlaudFile.HEADER_SIZE)
        }
        val plain = PlaudFileCrypto.decrypt(mixed, keys.second, dec)
        assertFalse(plain.contentEquals(ogg))
        assertFailsWith<IllegalArgumentException> { OggOpusDemuxer.demux(plain) }
    }

    @Test
    fun plaud_wrong_nonce_gives_garbage() {
        val ogg = ogg(1, 48_000, 2)
        val file = dec.encryptFile(keys.first, ogg)
        file[PlaudFile.OFF_CHACHA_NONCE] = (file[PlaudFile.OFF_CHACHA_NONCE] + 1).toByte()
        val plain = PlaudFileCrypto.decrypt(file, keys.second, dec)
        assertFalse(plain.contentEquals(ogg))
    }

    @Test
    fun plaud_tampered_ciphertext_is_not_authenticated_flip_propagates_one_byte() {
        // Plain ChaCha20 (no Poly1305): tampering is NOT detected by decrypt; it flips exactly the
        // same plaintext bit. Pin that so a future AEAD change is a conscious one.
        val ogg = ogg(1, 48_000, 2)
        val file = dec.encryptFile(keys.first, ogg)
        val at = PlaudFile.HEADER_SIZE + 40
        file[at] = (file[at].toInt() xor 0x01).toByte()
        val plain = PlaudFileCrypto.decrypt(file, keys.second, dec)
        val diffs = plain.indices.filter { plain[it] != ogg[it] }
        assertEquals(listOf(40), diffs)
    }

    @Test
    fun plaud_malformed_input_is_rejected() {
        val file = dec.encryptFile(keys.first, ogg(1, 48_000, 1))
        assertFailsWith<IllegalArgumentException> { PlaudFileCrypto.decrypt(file.copyOf(100), keys.second, dec) }
        assertFailsWith<IllegalArgumentException> {
            PlaudFileCrypto.decrypt(file.copyOf().also { it[0] = 'X'.code.toByte() }, keys.second, dec)
        }
        assertFailsWith<IllegalArgumentException> {
            PlaudFileCrypto.decrypt(file.copyOf().also { it[PlaudFile.OFF_VERSION] = 2 }, keys.second, dec)
        }
        // truncated body: decrypts to a prefix, which the demuxer must then reject
        val trunc = file.copyOf(file.size - 5)
        val plain = PlaudFileCrypto.decrypt(trunc, keys.second, dec)
        assertTrue(plain.size < file.size - PlaudFile.HEADER_SIZE)
        assertFailsWith<IllegalArgumentException> { OggOpusDemuxer.demux(plain) }
    }

    @Test
    fun plaud_segmented_body_resets_counter_per_segment() {
        val plainBody = ByteArray(300) { (it * 3).toByte() }
        val key = ByteArray(32) { 9 }
        val nonce = ByteArray(12) { 4 }
        val seg = dec.chacha20Decrypt(key, nonce, 1L, 128L, plainBody) // xor stream is symmetric
        val round = dec.chacha20Decrypt(key, nonce, 1L, 128L, seg)
        assertContentEquals(plainBody, round)
        // segment 2 keystream == segment 1 keystream (counter reset)
        assertContentEquals(
            seg.copyOfRange(0, 128).mapIndexed { i, b -> (b.toInt() xor plainBody[i].toInt()).toByte() },
            seg.copyOfRange(128, 256).mapIndexed { i, b -> (b.toInt() xor plainBody[128 + i].toInt()).toByte() },
        )
    }
}
