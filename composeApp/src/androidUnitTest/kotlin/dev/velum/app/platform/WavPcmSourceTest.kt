package dev.velum.app.platform

import dev.velum.app.playback.SkipSilenceScanner
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Skip-silence must read the WAV in bounded windows and never the whole data chunk (#333). */
class WavPcmSourceTest {
    private val dir = File(System.getProperty("java.io.tmpdir"), "velum-333-${System.nanoTime()}").apply { mkdirs() }

    /** A WAV whose PCM is produced by [sample] (frame index -> value); written in slices, sparse-free. */
    private fun wav(name: String, rate: Int, channels: Int, frames: Long, sample: (Long) -> Int): File {
        val f = File(dir, name)
        val dataSize = frames * channels * 2
        RandomAccessFile(f, "rw").use { raf ->
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()).putInt((36 + dataSize).toInt()).put("WAVE".toByteArray())
            h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort())
            h.putInt(rate).putInt(rate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
            h.put("data".toByteArray()).putInt(dataSize.toInt())
            raf.write(h.array())
            val slice = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN)
            var frame = 0L
            while (frame < frames) {
                slice.clear()
                while (slice.remaining() >= channels * 2 && frame < frames) {
                    repeat(channels) { slice.putShort(sample(frame).toShort()) }
                    frame++
                }
                raf.write(slice.array(), 0, slice.position())
            }
        }
        return f
    }

    @Test fun opening_reads_the_header_only() {
        val f = wav("big.wav", 16_000, 1, 16_000L * 600) { 1000 } // 10 minutes
        val source = assertNotNull(WavPcmSource.open(f))
        assertEquals(0L, source.bytesRead, "opening must not read any PCM")
    }

    @Test fun scanning_a_long_silence_reads_bounded_windows_and_matches_the_whole_buffer_scanner() {
        val rate = 16_000
        val silentFrames = rate * 120L // two minutes of silence, then a tone
        val total = silentFrames + rate * 5L
        val f = wav("silence.wav", rate, 1, total) { if (it < silentFrames) 0 else 4000 }
        val source = assertNotNull(WavPcmSource.open(f))
        val got = source.findSkipPastSilence(fromMs = 1_000L)
        // The same answer the old whole-buffer scanner gives.
        val pcm = f.readBytes().copyOfRange(44, f.length().toInt())
        val expected = SkipSilenceScanner.findSkipPastSilence(pcm, rate, 1_000L)
        assertEquals(expected, got)
        assertEquals(120_000L, got!!.seekToMs)
        assertTrue(source.largestRead <= WavPcmSource.WINDOW_BYTES, "one read was ${source.largestRead} bytes")
        // It scanned only the silence it needed, not the audible tail.
        assertTrue(source.bytesRead <= silentFrames * 2 + WavPcmSource.WINDOW_BYTES, "read ${source.bytesRead}")
    }

    @Test fun stereo_is_averaged_per_window_and_short_silence_is_not_skipped() {
        val rate = 8_000
        val f = wav("st.wav", rate, 2, rate * 3L) { if (it in 1_000L..1_400L) 0 else 3000 } // 50 ms of silence
        val source = assertNotNull(WavPcmSource.open(f))
        assertNull(source.findSkipPastSilence(fromMs = 125L))
        val long = wav("st2.wav", rate, 2, rate * 3L) { if (it in 1_000L..6_000L) 0 else 3000 }
        val got = assertNotNull(WavPcmSource.open(long)!!.findSkipPastSilence(fromMs = 125L))
        assertEquals(6_001L * 1000 / rate, got.seekToMs)
    }

    @Test fun all_silence_to_the_end_seeks_to_the_end_like_before() {
        val rate = 16_000
        val f = wav("quiet.wav", rate, 1, rate * 4L) { 0 }
        val pcm = f.readBytes().copyOfRange(44, f.length().toInt())
        assertEquals(SkipSilenceScanner.findSkipPastSilence(pcm, rate, 0L), WavPcmSource.open(f)!!.findSkipPastSilence(0L))
    }

    @Test fun non_pcm16_or_headerless_files_have_no_source() {
        assertNull(WavPcmSource.open(File(dir, "x.wav").apply { writeBytes(Random(1).nextBytes(100)) }))
        assertNull(WavPcmSource.open(File(dir, "tiny.wav").apply { writeBytes(ByteArray(10)) }))
    }

    private fun androidSource(name: String) = listOf(
        File("src/androidMain/kotlin/dev/velum/app/platform/$name"),
        File("composeApp/src/androidMain/kotlin/dev/velum/app/platform/$name"),
    ).first { it.isFile }.readText()

    /** MediaPlayer needs a device, so prepare() is guarded at the source: it must not load WAV data. */
    @Test fun prepare_never_loads_wav_data() {
        val src = androidSource("AudioPlayer.android.kt")
        assertTrue(!src.contains("readFully"), "the player must not read file data itself")
        assertTrue(!src.contains("loadWavPcm"), "no eager PCM load")
        assertTrue(!src.contains("pcm16LeMono"), "no whole-file PCM field")
        val prepare = src.substringAfter("override suspend fun prepare").substringBefore("override fun play()")
        assertTrue(!prepare.contains("WavPcmSource"), "prepare must not even open the WAV source")
    }

    private fun expected(f: File, rate: Int, ch: Int, from: Long): SkipSilenceScanner.Result? {
        // The whole-buffer answer: average the channels, then scan.
        val raw = f.readBytes().copyOfRange(44, f.length().toInt())
        val frames = raw.size / (2 * ch)
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val mono = ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) { var sum = 0; repeat(ch) { sum += bb.short.toInt() }; mono.putShort((sum / ch).toShort()) }
        return SkipSilenceScanner.findSkipPastSilence(mono.array(), rate, from)
    }

    @Test fun stereo_longer_than_one_window_and_a_sample_exactly_at_a_window_boundary() {
        val rate = 48_000
        val window = WavPcmSource.WINDOW_BYTES / 4 // stereo frames per window
        for (audibleAt in listOf(window.toLong(), window - 1L, window + 1L, 3L * window)) {
            val f = wav("b$audibleAt.wav", rate, 2, 5L * window) { if (it < audibleAt) 0 else 3000 }
            assertEquals(expected(f, rate, 2, 0L), WavPcmSource.open(f)!!.findSkipPastSilence(0L), "audible at $audibleAt")
        }
    }

    @Test fun data_size_larger_than_the_file_and_odd_length_data_are_clamped() {
        val f = wav("big.wav", 8_000, 1, 8_000L * 3) { if (it < 16_000L) 0 else 3000 }
        RandomAccessFile(f, "rw").use { it.seek(40); it.write(byteArrayOf(0x7F, 0x7F, 0x7F, 0x7F)) } // claims ~2 GB
        assertEquals(2_000L, WavPcmSource.open(f)!!.findSkipPastSilence(0L)!!.seekToMs)
        val odd = wav("odd.wav", 8_000, 1, 8_000L * 3) { if (it < 16_000L) 0 else 3000 }
        RandomAccessFile(odd, "rw").use { it.setLength(odd.length() - 1) } // an odd number of data bytes
        assertEquals(2_000L, WavPcmSource.open(odd)!!.findSkipPastSilence(0L)!!.seekToMs)
    }

    private fun headerWith(format: Int, bits: Int, channels: Int): File {
        val f = wav("hdr-$format-$bits-$channels.wav", 16_000, 1, 1_000) { 0 }
        RandomAccessFile(f, "rw").use { raf ->
            val b = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            b.putShort(format.toShort()).putShort(channels.toShort()).putInt(16_000).putInt(16_000 * 2).putShort(2).putShort(bits.toShort())
            raf.seek(20); raf.write(b.array())
        }
        return f
    }

    @Test fun real_8_bit_24_bit_float_and_absurd_channel_headers_have_no_source() {
        assertNull(WavPcmSource.open(headerWith(1, 8, 1)))
        assertNull(WavPcmSource.open(headerWith(1, 24, 1)))
        assertNull(WavPcmSource.open(headerWith(3, 16, 1)), "IEEE float / non-PCM")
        assertNull(WavPcmSource.open(headerWith(1, 16, 65_535)), "channel count overflowing a window")
        assertNull(WavPcmSource.open(headerWith(1, 16, 0)))
    }

    @Test fun a_cancelled_scan_stops_between_windows() {
        val rate = 16_000
        val f = wav("cancel.wav", rate, 1, rate * 600L) { 0 } // ten minutes of silence: many windows
        val source = assertNotNull(WavPcmSource.open(f))
        var windows = 0
        val stopped = try {
            source.findSkipPastSilence(0L) { if (++windows == 3) throw kotlinx.coroutines.CancellationException("stop") }
            false
        } catch (_: kotlinx.coroutines.CancellationException) {
            true
        }
        assertTrue(stopped)
        assertTrue(source.bytesRead <= 2L * WavPcmSource.WINDOW_BYTES, "read ${source.bytesRead} bytes after cancelling at window 3")
    }

    @Test fun an_unreadable_data_chunk_means_no_skip_not_a_crash() {
        val f = wav("trunc.wav", 16_000, 1, 16_000L * 10) { 0 }
        val source = WavPcmSource.open(f)!!
        f.delete() // the file vanishes under the open source
        assertNull(source.findSkipPastSilence(0L))
    }

    @Test fun the_player_cancels_stale_scans_catches_failures_and_rechecks_before_seeking() {
        val src = androidSource("AudioPlayer.android.kt")
        val prepare = src.substringAfter("override suspend fun prepare").substringBefore("override fun play()")
        assertTrue(prepare.contains("skipScan?.cancel()"), "prepare cancels a scan of the previous file")
        val scan = src.substringAfter("private fun maybeSkipSilence")
        assertTrue(scan.contains("catch (_: Exception)"), "an IO failure must not escape the scope")
        assertTrue(scan.contains("wavFile !== file"), "re-check the file before using the result")
        assertTrue(scan.contains("SkipSilenceScanner.shouldApplySkip("), "the late-seek rule is the tested pure function")
        assertTrue(scan.contains("catch (c: CancellationException)") && !scan.contains("runCatching"), "cancellation is never swallowed")
    }
}
