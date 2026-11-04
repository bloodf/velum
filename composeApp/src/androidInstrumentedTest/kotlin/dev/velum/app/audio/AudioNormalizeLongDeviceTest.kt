package dev.velum.app.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.velum.app.platform.AudioNormalizer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #319 on a device: normalizes a long synthetic stereo 44.1 kHz AAC/M4A through the real MediaExtractor/MediaCodec
 * path and reports, per length, the decode time and the peak Java heap growth while it ran. Checks:
 * - heap: the peak of the heap in use after a GC (memory pools' collection usage, so uncollected garbage is not
 *   counted) stays under [MAX_HEAP_GROWTH_BYTES], far below the PCM size of the longest input; a positive control
 *   that retains a PCM-sized array proves the probe can see it. Native codec buffers are NOT counted by this probe.
 * - time: at least two lengths spanning 4x or more, time per audio minute within [MAX_RATE_RATIO] across lengths and
 *   under [MAX_MS_PER_AUDIO_MINUTE] absolutely.
 * The input is generated here by the device's own AAC encoder (no recorded audio).
 *
 * Skipped unless `normMinutes` is given (comma separated, e.g. `5,30,60`). Expected runtime: encoding is about 3x
 * realtime and normalizing about 9x realtime on the arm64 emulator, so 60 min of audio takes about 30 min.
 *
 * Run: adb -s emulator-5554 shell am instrument -w -e class dev.velum.app.audio.AudioNormalizeLongDeviceTest \
 *   -e normMinutes 5,15,60 -e velumDeviceWalk false dev.velum.app.test/dev.velum.app.devicewalk.DeviceWalkTestRunner
 */
@RunWith(AndroidJUnit4::class)
class AudioNormalizeLongDeviceTest {
    private val args = InstrumentationRegistry.getArguments()

    @Test(timeout = 5_400_000) fun long_stereo_m4a_normalizes_in_linear_time_with_bounded_heap() {
        val raw = args.getString("normMinutes")
        assumeTrue("normMinutes not given", raw != null)
        val minutes = raw.orEmpty().split(',').map { it.trim().toIntOrNull() }
        // A given but malformed argument must fail, not silently skip the whole check.
        assertTrue("normMinutes '$raw' must be comma separated positive integers", minutes.isNotEmpty() && minutes.all { it != null && it > 0 })
        val minutes2 = minutes.filterNotNull()
        assumeTrue("need two lengths spanning at least 4x for the linearity check", minutes2.max() >= 4 * minutes2.min())
        positiveControl()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.cacheDir, "norm319").apply { deleteRecursively(); mkdirs() }
        val lines = mutableListOf<String>()
        val rates = mutableListOf<Double>()
        val growth = mutableListOf<Long>()
        try {
            for (m in minutes2) {
                val src = File(dir, "stereo-${m}min.m4a")
                val encodeMs = time { encodeStereoM4a(src, m * 60) }
                val baseline = heapAfterGc()
                val peak = AtomicLong(0)
                val running = AtomicBoolean(true)
                val sampler = Thread {
                    while (running.get()) {
                        peak.accumulateAndGet(heapAfterGc() - baseline) { a, b -> maxOf(a, b) }
                        Thread.sleep(SAMPLE_EVERY_MS)
                    }
                }.apply { isDaemon = true; start() }
                var normalized: dev.velum.app.platform.NormalizedAudio? = null
                val normMs = try {
                    time { normalized = runBlocking { AudioNormalizer().normalizeToWav(src.absolutePath, 16_000) } }
                } finally {
                    running.set(false); sampler.join(2_000)
                }
                val wav = File(normalized!!.wavPath)
                val expectedBytes = m * 60L * 16_000 * 2
                assertTrue("wav size ${wav.length()} vs ~$expectedBytes", kotlin.math.abs(wav.length() - expectedBytes) < 16_000L * 2 * 2)
                val line = "minutes=$m srcKiB=${src.length() shr 10} encodeMs=$encodeMs normalizeMs=$normMs " +
                    "x_realtime=${"%.1f".format(java.util.Locale.ROOT, m * 60_000.0 / normMs)} wavMiB=${wav.length() shr 20} " +
                    "peakHeapAfterGcGrowthKiB=${peak.get() shr 10}"
                Log.i(TAG, line); lines += line
                rates += normMs.toDouble() / m
                growth += peak.get()
                wav.delete(); src.delete()
            }
        } finally {
            dir.deleteRecursively()
            // Reported even when an assertion below fails, so a failing run still shows its timings.
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("velum.norm319", lines.joinToString(" || ")) })
        }
        assertTrue("heap growth ${growth.map { it shr 20 }} MiB exceeds ${MAX_HEAP_GROWTH_BYTES shr 20} MiB", growth.all { it < MAX_HEAP_GROWTH_BYTES })
        assertTrue("normalize took more than ${MAX_MS_PER_AUDIO_MINUTE} ms per audio minute: $rates", rates.all { it < MAX_MS_PER_AUDIO_MINUTE })
        assertTrue("time per audio minute not linear: $rates", rates.max() <= rates.min() * MAX_RATE_RATIO)
    }

    /** Java heap in use right after a forced GC (java.lang.management does not exist on Android). */
    private fun heapAfterGc(): Long {
        System.gc()
        val rt = Runtime.getRuntime()
        return rt.totalMemory() - rt.freeMemory()
    }

    /** The probe must see a retained PCM-sized array, otherwise a low reading means nothing. */
    private fun positiveControl() {
        val before = heapAfterGc()
        val held = ByteArray(64 * 1024 * 1024).also { it[0] = 1 }
        val seen = heapAfterGc() - before
        java.lang.ref.Reference.reachabilityFence(held)
        assertTrue("probe saw only ${seen shr 20} MiB of a retained 64 MiB array", seen > 48L * 1024 * 1024)
    }

    private inline fun time(block: () -> Unit): Long { val t = SystemClock.elapsedRealtime(); block(); return SystemClock.elapsedRealtime() - t }

    /** Stereo 44.1 kHz AAC-LC in M4A, [seconds] long: two different tones with a slow amplitude wobble. */
    private fun encodeStereoM4a(dest: File, seconds: Int) {
        val rate = 44_100
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var track = -1
        var muxerStarted = false
        val info = MediaCodec.BufferInfo()
        try {
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also { codec = it }
            val mux = MediaMuxer(dest.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { muxer = it }
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
            val framesPerChunk = 2048
            val totalFrames = seconds.toLong() * rate
            var frame = 0L
            var inputDone = false
            var outputDone = false
            val chunk = ByteBuffer.allocate(framesPerChunk * 4).order(ByteOrder.LITTLE_ENDIAN)
            while (!outputDone) {
                if (!inputDone) {
                    val i = c.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = minOf(framesPerChunk.toLong(), totalFrames - frame).toInt()
                        val buf = c.getInputBuffer(i)!!
                        buf.clear()
                        chunk.clear()
                        for (k in 0 until n) {
                            val t = (frame + k).toDouble() / rate
                            val wobble = 0.6 + 0.3 * sin(2 * PI * 0.2 * t)
                            chunk.putShort((sin(2 * PI * 440 * t) * 9000 * wobble).toInt().toShort())
                            chunk.putShort((sin(2 * PI * 660 * t) * 9000 * wobble).toInt().toShort())
                        }
                        chunk.flip(); buf.put(chunk)
                        val pts = frame * 1_000_000L / rate
                        frame += n
                        inputDone = frame >= totalFrames
                        c.queueInputBuffer(i, 0, n * 4, pts, if (inputDone) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                    }
                }
                val o = c.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = mux.addTrack(c.outputFormat); mux.start(); muxerStarted = true }
                    o >= 0 -> {
                        val out = c.getOutputBuffer(o)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && muxerStarted) { out.position(info.offset); out.limit(info.offset + info.size); mux.writeSampleData(track, out, info) }
                        c.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            runCatching { codec?.stop() }; codec?.release()
            runCatching { if (muxerStarted) muxer?.stop() }; muxer?.release()
        }
        assertEquals(true, dest.length() > 0)
    }

    private companion object {
        const val TAG = "VelumNorm319"
        const val MAX_HEAP_GROWTH_BYTES = 32L * 1024 * 1024 // the 60 min input is ~110 MiB of 16 kHz PCM, 635 MB at 44.1 kHz stereo
        // Each sample forces a GC, which pauses decoding, so sample rarely: a retained PCM array stays visible.
        const val SAMPLE_EVERY_MS = 2_000L
        const val MAX_RATE_RATIO = 2.0
        const val MAX_MS_PER_AUDIO_MINUTE = 20_000.0 // observed ~6,600 on the emulator
    }
}
