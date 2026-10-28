package dev.velum.ai.audio

import dev.velum.ai.local.ModelStoreDir
import dev.velum.ai.pipeline.RecordingProcessor
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Large synthetic Ogg Opus / WAV streams through instrumented readers — no single read or
 * retained buffer may approach the full file size.
 */
class StreamDecodeBoundTest {
    @Test
    fun demuxAtForEach_largeOgg_readsStayPageBounded() {
        val pages = 80
        val packetPayload = ByteArray(200) { 0x5A }
        val fileBytes = buildLargeOgg(pages, packetPayload)
        assertTrue(fileBytes.size > 16_000, "synthetic Ogg should be clearly larger than a page")

        val tracker = TrackingReadAt(fileBytes)
        var packets = 0
        var maxPacket = 0
        OggOpusDemuxer.demuxAtForEach(
            size = fileBytes.size.toLong(),
            readAt = tracker::readAt,
            onPacket = {
                packets++
                maxPacket = maxOf(maxPacket, it.size)
            },
        )
        assertEquals(pages, packets)
        // Ogg page body ≤ 255×255; header+table overhead keeps a single read well under 70 KiB.
        val maxAllowed = 70 * 1024
        assertTrue(
            tracker.maxRead <= maxAllowed,
            "max read ${tracker.maxRead} exceeded page bound $maxAllowed (file=${fileBytes.size})",
        )
        assertTrue(tracker.maxRead < fileBytes.size / 4)
        assertTrue(maxPacket <= 255, "single-segment packets must be <255 bytes")
        assertTrue(tracker.totalBytesRead >= fileBytes.size.toLong())
    }

    @Test
    fun decodeStreamingFromReader_largeOgg_readsStayPageBounded() {
        val pages = 40
        val fileBytes = buildLargeOgg(pages, ByteArray(80) { 0x11 })
        assertTrue(fileBytes.size > 4_000)

        val dir = File.createTempFile("velum-ogg-bound", null).apply {
            delete()
            mkdirs()
        }
        try {
            val dest = ModelStoreDir(dir.absolutePath).resolve("out.s16le")
            val tracker = TrackingReadAt(fileBytes)
            // Invalid Opus payloads may decode to silence/empty frames; we only assert read bounds.
            runCatching {
                KopusOpusDecoder().decodeStreamingFromReader(
                    size = fileBytes.size.toLong(),
                    readAt = tracker::readAt,
                    dest = dest,
                )
            }
            val maxAllowed = 70 * 1024
            assertTrue(
                tracker.maxRead <= maxAllowed,
                "max read ${tracker.maxRead} exceeded page bound $maxAllowed",
            )
            assertTrue(tracker.maxRead < fileBytes.size / 2)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun streamCopyRange_largeWavPayload_readsStayChunkBounded() {
        val pcmSize = 2 * 1024 * 1024
        val wav = buildWav(pcmSize)
        assertTrue(wav.size > pcmSize)

        val tracker = TrackingByteReadAt(wav)
        val chunkBytes = RecordingProcessor.STREAM_COPY_CHUNK_BYTES
        var written = 0
        RecordingProcessor.streamCopyRange(
            start = 44L,
            length = pcmSize.toLong(),
            chunkBytes = chunkBytes,
            readAt = tracker::readAt,
            writeAll = { written = 0 },
            append = { chunk ->
                written += chunk.size
                assertTrue(
                    chunk.size <= chunkBytes,
                    "append buffer ${chunk.size} exceeded chunk $chunkBytes",
                )
            },
        )
        assertEquals(pcmSize, written)
        assertTrue(
            tracker.maxRead <= chunkBytes,
            "max read ${tracker.maxRead} exceeded chunk $chunkBytes",
        )
        assertTrue(tracker.maxRead < wav.size / 4)
    }

    @Test
    fun readAtModelStore_neverReturnsMoreThanRequested() {
        val dir = File.createTempFile("velum-readat", null).apply {
            delete()
            mkdirs()
        }
        try {
            val file = ModelStoreDir(dir.absolutePath).resolve("blob.bin")
            val payload = ByteArray(100_000) { it.toByte() }
            file.writeBytes(payload)
            val buf = ByteArray(8_000)
            val n = readAtModelStore(file, payload.size.toLong(), buf, 10_000L, 4_000)
            assertEquals(4_000, n)
            assertEquals(payload[10_000], buf[0])
            assertEquals(payload[10_000 + 3_999], buf[3_999])
        } finally {
            dir.deleteRecursively()
        }
    }

    private class TrackingReadAt(private val bytes: ByteArray) {
        var maxRead: Int = 0
            private set
        var totalBytesRead: Long = 0
            private set

        fun readAt(dest: ByteArray, position: Long, length: Int): Int {
            maxRead = maxOf(maxRead, length)
            val pos = position.toInt()
            if (pos >= bytes.size) return -1
            val n = minOf(length, bytes.size - pos)
            if (n <= 0) return -1
            bytes.copyInto(dest, destinationOffset = 0, startIndex = pos, endIndex = pos + n)
            totalBytesRead += n.toLong()
            return n
        }
    }

    private class TrackingByteReadAt(private val bytes: ByteArray) {
        var maxRead: Int = 0
            private set

        fun readAt(offset: Long, length: Int): ByteArray {
            maxRead = maxOf(maxRead, length)
            val start = offset.toInt()
            return bytes.copyOfRange(start, start + length)
        }
    }

    private fun buildLargeOgg(audioPages: Int, packet: ByteArray): ByteArray {
        require(packet.size < 255)
        val head = byteArrayOf(
            'O'.code.toByte(), 'p'.code.toByte(), 'u'.code.toByte(), 's'.code.toByte(),
            'H'.code.toByte(), 'e'.code.toByte(), 'a'.code.toByte(), 'd'.code.toByte(),
            1, 1, 0, 0,
            0x80.toByte(), 0xBB.toByte(), 0x00, 0x00,
            0, 0, 0,
        )
        val tags = byteArrayOf(
            'O'.code.toByte(), 'p'.code.toByte(), 'u'.code.toByte(), 's'.code.toByte(),
            'T'.code.toByte(), 'a'.code.toByte(), 'g'.code.toByte(), 's'.code.toByte(),
            0, 0, 0, 0, // vendor length 0
            0, 0, 0, 0, // comment count 0
        )
        val parts = ArrayList<ByteArray>(audioPages + 2)
        parts += buildOggPage(head)
        parts += buildOggPage(tags)
        repeat(audioPages) { parts += buildOggPage(packet) }
        val total = parts.sumOf { it.size }
        val out = ByteArray(total)
        var o = 0
        for (p in parts) {
            p.copyInto(out, o)
            o += p.size
        }
        return out
    }

    private fun buildOggPage(packet: ByteArray): ByteArray {
        val header = ByteArray(27 + 1 + packet.size)
        header[0] = 'O'.code.toByte()
        header[1] = 'g'.code.toByte()
        header[2] = 'g'.code.toByte()
        header[3] = 'S'.code.toByte()
        header[26] = 1
        header[27] = packet.size.toByte()
        packet.copyInto(header, 28)
        return header
    }

    private fun buildWav(pcmBytes: Int): ByteArray {
        val out = ByteArray(44 + pcmBytes)
        "RIFF".toByteArray().copyInto(out, 0)
        writeInt(out, 4, 36 + pcmBytes)
        "WAVE".toByteArray().copyInto(out, 8)
        "fmt ".toByteArray().copyInto(out, 12)
        writeInt(out, 16, 16)
        out[20] = 1
        out[22] = 1
        writeInt(out, 24, 16_000)
        writeInt(out, 28, 32_000)
        out[32] = 2
        out[34] = 16
        "data".toByteArray().copyInto(out, 36)
        writeInt(out, 40, pcmBytes)
        return out
    }

    private fun writeInt(buf: ByteArray, at: Int, value: Int) {
        buf[at] = (value and 0xff).toByte()
        buf[at + 1] = ((value ushr 8) and 0xff).toByte()
        buf[at + 2] = ((value ushr 16) and 0xff).toByte()
        buf[at + 3] = ((value ushr 24) and 0xff).toByte()
    }

}
