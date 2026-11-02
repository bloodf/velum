package dev.velum.ai.audio

import dev.velum.ai.local.ModelStoreDir
import dev.velum.ble.crypto.JcaFileDecryptor
import dev.velum.ble.crypto.rsaKeyPairToPem
import dev.velum.protocol.PlaudFileCrypto
import eu.buney.kopus.OpusApplication
import eu.buney.kopus.OpusEncoder
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Synthetic chain for #86: Opus-encoded sine -> Ogg -> PLAUD.AI encrypt -> chunked
 * stream decrypt to disk -> streaming Opus decode to .s16le. No real recordings.
 */
class StreamingDownloadDecodeTest {
    private val rate = 16_000
    private val frame = rate / 50

    private fun syntheticOgg(seconds: Int): ByteArray {
        val pages = java.io.ByteArrayOutputStream()
        val head = ByteArray(19).also {
            "OpusHead".encodeToByteArray().copyInto(it)
            it[8] = 1
            it[9] = 1
            it[12] = (rate and 0xff).toByte()
            it[13] = ((rate shr 8) and 0xff).toByte()
        }
        pages.write(oggPage(head))
        pages.write(oggPage("OpusTags".encodeToByteArray() + ByteArray(8)))
        OpusEncoder(rate, 1, OpusApplication.Voip).use { enc ->
            val pcm = ShortArray(frame)
            val out = ByteArray(1275)
            var t = 0
            repeat(seconds * 50) {
                for (i in pcm.indices) {
                    pcm[i] = (sin(2 * PI * 440 * t++ / rate) * 8000).toInt().toShort()
                }
                val n = enc.encode(inPcm = pcm, inPcmOffset = 0, frameSize = frame, outData = out, outDataOffset = 0, maxDataBytes = out.size)
                require(n in 1..254)
                pages.write(oggPage(out.copyOf(n)))
            }
        }
        return pages.toByteArray()
    }

    private fun oggPage(packet: ByteArray): ByteArray {
        require(packet.size < 255)
        val page = ByteArray(28 + packet.size)
        "OggS".encodeToByteArray().copyInto(page)
        page[26] = 1
        page[27] = packet.size.toByte()
        packet.copyInto(page, 28)
        return page
    }

    @Test
    fun encrypted_opus_streams_to_disk_and_decodes_to_expected_pcm() {
        val dir = ModelStoreDir(Files.createTempDirectory("velum-86-chain").toString())
        try {
            val (pub, priv) = rsaKeyPairToPem()
            val decryptor = JcaFileDecryptor()
            val ogg = syntheticOgg(seconds = 20)
            val enc = dir.resolve("session.bin").also { it.writeBytes(decryptor.encryptFile(pub, ogg)) }
            val oggOut = dir.resolve("rec.part").also { it.writeBytes(ByteArray(0)) }
            var maxPiece = 0
            val total = PlaudFileCrypto.decryptStream(
                size = enc.length(),
                readAt = { p, l -> enc.readRange(p, l) },
                privateKeyPem = priv,
                decryptor = decryptor,
                chunkBytes = 4096,
            ) {
                maxPiece = maxOf(maxPiece, it.size)
                oggOut.appendBytes(it)
            }
            assertEquals(ogg.size.toLong(), total)
            assertTrue(maxPiece <= 4096)
            assertContentEquals(ogg, oggOut.readBytes())

            val pcm = dir.resolve("pcm.s16le")
            val meta = KopusOpusDecoder().decodePathToPcmFile(oggOut.absolutePath, pcm)
            val expected = KopusOpusDecoder.decodeInMemory(ogg)
            assertEquals(rate, meta.sampleRateHz)
            assertEquals(1, meta.channels)
            assertEquals(20L * rate * 2, meta.sizeBytes, "golden length: 20 s mono s16le")
            // Streaming path must equal the in-memory reference byte for byte.
            assertContentEquals(expected.toByteArray(), pcm.readBytes())
            assertTrue(expected.samples.any { it > 1000 }, "decoded sine is not silence")
            val fromBytes = dir.resolve("from-bytes.s16le")
            KopusOpusDecoder().decodeToPcmFile(ogg, fromBytes)
            assertContentEquals(pcm.readBytes(), fromBytes.readBytes(), "byte[] and path decode agree")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun corrupt_ogg_page_fails_explicitly_from_disk() {
        val dir = ModelStoreDir(Files.createTempDirectory("velum-86-corrupt").toString())
        try {
            val ogg = syntheticOgg(seconds = 1)
            val third = ogg.indices.filter { i ->
                i + 3 < ogg.size && ogg[i] == 'O'.code.toByte() && ogg[i + 1] == 'g'.code.toByte() &&
                    ogg[i + 2] == 'g'.code.toByte() && ogg[i + 3] == 'S'.code.toByte()
            }[3]
            val corrupt = ogg.copyOf().also { it[third] = 'X'.code.toByte() }
            val src = dir.resolve("bad.ogg").also { it.writeBytes(corrupt) }
            val e = assertFailsWith<IllegalArgumentException> {
                KopusOpusDecoder().decodePathToPcmFile(src.absolutePath, dir.resolve("pcm.s16le"))
            }
            assertTrue(e.message!!.contains("Invalid Ogg page magic"), e.message)
            assertContentEquals(corrupt, src.readBytes(), "source recording preserved")
        } finally {
            dir.deleteRecursively()
        }
    }
}
