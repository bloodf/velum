package dev.velum.app.platform

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The streaming writer must give the same samples as the old whole-buffer code, in time linear in the input (#319). */
class MonoPcmWavWriterTest {
    private val dir = File(System.getProperty("java.io.tmpdir"), "velum-319-${System.nanoTime()}").apply { mkdirs() }

    // The former implementation, kept here as the reference.
    private fun refMono(interleaved: ByteArray, channels: Int): ByteArray {
        if (channels <= 1) return interleaved
        val frames = interleaved.size / 2 / channels
        val out = ByteArray(frames * 2)
        val bb = ByteBuffer.wrap(interleaved).order(ByteOrder.LITTLE_ENDIAN)
        val ob = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) {
            var sum = 0
            repeat(channels) { sum += bb.short.toInt() }
            ob.putShort((sum / channels).toShort())
        }
        return out
    }

    private fun refResample(input: ByteArray, from: Int, to: Int): ByteArray {
        if (from == to || input.isEmpty()) return input
        val inS = ShortArray(input.size / 2)
        ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(inS)
        val outLen = ((inS.size.toLong() * to) / from).toInt().coerceAtLeast(1)
        val outS = ShortArray(outLen)
        for (i in 0 until outLen) {
            val src = i.toDouble() * from / to
            val i0 = src.toInt().coerceIn(0, inS.lastIndex)
            val i1 = (i0 + 1).coerceAtMost(inS.lastIndex)
            val frac = src - i0
            val s = inS[i0] * (1.0 - frac) + inS[i1] * frac
            outS[i] = s.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        val out = ByteArray(outLen * 2)
        ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(outS)
        return out
    }

    private fun pcmOf(file: File): ByteArray = file.readBytes().copyOfRange(44, file.length().toInt())

    private fun run(pcm: ByteArray, channels: Int, from: Int, to: Int, chunkSizes: IntArray): ByteArray {
        val f = File(dir, "out-${System.nanoTime()}.wav")
        MonoPcmWavWriter(f, to).use { w ->
            w.setFormat(from, channels)
            var at = 0
            var i = 0
            while (at < pcm.size) {
                val n = minOf(chunkSizes[i++ % chunkSizes.size], pcm.size - at)
                w.write(pcm.copyOfRange(at, at + n))
                at += n
            }
            w.finish()
        }
        return pcmOf(f)
    }

    @Test fun matches_the_whole_buffer_code_for_odd_chunking_rates_and_channels() {
        val random = Random(319)
        for ((from, to, ch) in listOf(Triple(44_100, 16_000, 2), Triple(48_000, 16_000, 1), Triple(8_000, 16_000, 1), Triple(22_050, 16_000, 3), Triple(16_000, 16_000, 2), Triple(44_100, 16_000, 1))) {
            for (frames in listOf(1, 2, 3, 7, 100, 4_321)) {
                val pcm = random.nextBytes(frames * ch * 2)
                val expected = refResample(refMono(pcm, ch), from, to)
                // Chunk sizes that split frames and samples: 1, 3, 7, 4097 bytes.
                val actual = run(pcm, ch, from, to, intArrayOf(1, 3, 7, 4097, 2, 64))
                assertContentEquals(expected, actual, "from=$from to=$to ch=$ch frames=$frames")
            }
        }
    }

    @Test fun header_is_patched_with_the_final_size() {
        val f = File(dir, "hdr.wav")
        MonoPcmWavWriter(f, 16_000).use { w ->
            w.setFormat(16_000, 1)
            w.write(ByteArray(1_000))
            w.finish()
        }
        val bb = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(36 + 1_000, bb.getInt(4))
        assertEquals(16_000, bb.getInt(24))
        assertEquals(1_000, bb.getInt(40))
        assertEquals(1_044L, f.length())
    }

    @Test fun a_format_change_midstream_finishes_the_first_stage() {
        val a = Random(1).nextBytes(3_000)
        val b = Random(2).nextBytes(2_000)
        val f = File(dir, "two.wav")
        MonoPcmWavWriter(f, 16_000).use { w ->
            w.setFormat(48_000, 1); w.write(a)
            w.setFormat(16_000, 1); w.write(b)
            w.finish()
        }
        assertContentEquals(refResample(a, 48_000, 16_000) + b, pcmOf(f))
    }

    /**
     * A guard, not a performance test: 60 minutes of 44.1 kHz stereo generated chunk by chunk (never held) must
     * produce the exact length. The generous time limit only catches a return of quadratic copying, which would
     * take far longer; it says nothing about speed on a phone.
     */
    @Test fun sixty_minutes_of_stereo_streams_without_holding_it_and_in_linear_time_guard() {
        val f = File(dir, "long.wav")
        val chunk = ByteArray(32 * 1024) { (it * 7).toByte() }
        val totalBytes = 60L * 60 * 44_100 * 2 * 2
        var sentBytes = 0L
        val started = System.nanoTime()
        MonoPcmWavWriter(f, 16_000).use { w ->
            w.setFormat(44_100, 2)
            var sent = 0L
            while (sent < totalBytes) { w.write(chunk); sent += chunk.size }
            sentBytes = sent
            w.finish()
        }
        val seconds = (System.nanoTime() - started) / 1e9
        val expectedSamples = (sentBytes / 4) * 16_000 / 44_100
        assertEquals(44 + expectedSamples * 2, f.length())
        // The quadratic fold needed hundreds of GB of copying; linear streaming does this in seconds.
        assertTrue(seconds < 60, "took $seconds s")
        f.delete()
    }

    @Test fun releaseAll_runs_every_step_even_when_one_throws() {
        val ran = mutableListOf<Int>()
        releaseAll({ ran += 1 }, { error("boom") }, { ran += 3 })
        assertEquals(listOf(1, 3), ran)
        assertFailsWith<IllegalStateException> { MonoPcmWavWriter(File(dir, "x.wav"), 16_000).let { it.finish(); it.write(ByteArray(2)) } }
    }

    private fun androidSource(name: String) = listOf(
        File("src/androidMain/kotlin/dev/velum/app/platform/$name"),
        File("composeApp/src/androidMain/kotlin/dev/velum/app/platform/$name"),
    ).first { it.isFile }.readText()

    /** Source guard (the decode loop needs a codec, which no JVM test has): nothing may accumulate or read whole. */
    @Test fun a_failure_before_the_writer_exists_must_not_delete_an_earlier_output_and_the_retriever_is_released() {
        val src = androidSource("AudioNormalize.android.kt")
        val body = src.substringAfter("private fun normalizeBlocking").substringBefore("private fun durationHintMs")
        assertTrue(body.contains("var writerCreated = false") && body.contains("if (writerCreated) outFile.delete()"))
        assertTrue(body.indexOf("writerCreated = true") < body.indexOf("onOutput(outFile)"), "report the output only once the writer owns it")
        val hint = src.substringAfter("private fun durationHintMs").substringBefore("private fun decodeWithMediaCodec")
        assertTrue(hint.contains("} finally {") && hint.contains("r.release()"), "MediaMetadataRetriever released in finally")
    }

    @Test fun normalizer_checks_for_cancellation_in_both_loops_and_deletes_the_wav_when_cancelled() {
        val src = androidSource("AudioNormalize.android.kt")
        assertTrue(src.substringAfter("while (!outputDone) {").take(200).contains("checkCancelled()"), "decode loop")
        assertTrue(src.substringAfter("while (left > 0) {").take(120).contains("checkCancelled()"), "WAV copy loop")
        assertTrue(src.contains("catch (c: CancellationException)") && src.contains("outFile?.delete()"), "late cancellation")
    }

    @Test fun normalizer_never_accumulates_chunks_or_reads_the_source_whole_and_always_releases() {
        val src = androidSource("AudioNormalize.android.kt")
        assertTrue(!src.contains(".fold("), "decoded chunks must be written as they arrive")
        assertTrue(!src.contains("readBytes()"), "no whole-file read in the normalizer")
        assertTrue(!src.contains("pcmChunks"), "no chunk list")
        val decode = src.substringAfter("private fun decodeWithMediaCodec").substringBefore("private fun streamWavData")
        assertTrue(decode.contains("} finally {") && decode.contains("releaseAll("), "codec and extractor are released in finally")
        assertTrue(decode.contains("codec.release") || decode.contains("c?.release()"))
    }

    private fun wav(fmtBits: Int = 16, withData: Boolean = true, rate: Int = 16_000, ch: Int = 1, pcm: ByteArray = ByteArray(10)): File {
        val body = ByteBuffer.allocate(4 + 8 + 16 + (if (withData) 8 + pcm.size else 0)).order(ByteOrder.LITTLE_ENDIAN)
        body.put("WAVE".toByteArray()).put("fmt ".toByteArray()).putInt(16)
        body.putShort(1).putShort(ch.toShort()).putInt(rate).putInt(rate * ch * fmtBits / 8).putShort((ch * fmtBits / 8).toShort()).putShort(fmtBits.toShort())
        if (withData) body.put("data".toByteArray()).putInt(pcm.size).put(pcm)
        val f = File(dir, "h-${System.nanoTime()}.wav")
        f.writeBytes("RIFF".toByteArray() + ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(body.capacity()).array() + body.array())
        return f
    }

    @Test fun wav_header_is_read_by_seeking_and_reports_the_bit_depth_instead_of_failing() {
        val h = readWavHeader(wav(rate = 44_100, ch = 2, pcm = ByteArray(100)))!!
        assertEquals(44_100, h.sampleRateHz); assertEquals(2, h.channels); assertEquals(100L, h.dataSize)
        assertEquals(44L, h.dataOffset)
        assertEquals(null, readWavHeader(File(dir, "n.txt").apply { writeText("not a wav file at all") }))
        assertEquals(null, readWavHeader(wav(withData = false)), "no data chunk: the decoder reports it")
        assertEquals(8, readWavHeader(wav(fmtBits = 8))!!.bitsPerSample, "8-bit is reported, not rejected")
    }
}
