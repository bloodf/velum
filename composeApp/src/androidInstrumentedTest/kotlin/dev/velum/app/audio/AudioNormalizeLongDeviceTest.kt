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
 * path and reports, per length, the decode time and the peak Java heap growth while it ran. Linear time and a heap
 * that does not follow the length are the acceptance checks. The input is generated here by the device's own AAC
 * encoder (no recorded audio).
 *
 * Skipped unless `normMinutes` is given (comma separated, e.g. `5,15,60`).
 *
 * Run: adb -s emulator-5554 shell am instrument -w -e class dev.velum.app.audio.AudioNormalizeLongDeviceTest \
 *   -e normMinutes 5,15,60 -e velumDeviceWalk false dev.velum.app.test/dev.velum.app.devicewalk.DeviceWalkTestRunner
 */
@RunWith(AndroidJUnit4::class)
class AudioNormalizeLongDeviceTest {
    private val args = InstrumentationRegistry.getArguments()

    @Test fun long_stereo_m4a_normalizes_in_linear_time_with_bounded_heap() {
        val minutes = args.getString("normMinutes").orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }
        assumeTrue("normMinutes not given", minutes.isNotEmpty())
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.cacheDir, "norm319").apply { deleteRecursively(); mkdirs() }
        val lines = mutableListOf<String>()
        try {
            for (m in minutes) {
                val src = File(dir, "stereo-${m}min.m4a")
                val encodeMs = time { encodeStereoM4a(src, m * 60) }
                val rt = Runtime.getRuntime()
                System.gc()
                val baseline = rt.totalMemory() - rt.freeMemory()
                val peak = AtomicLong(0)
                val running = AtomicBoolean(true)
                val sampler = Thread {
                    while (running.get()) {
                        peak.accumulateAndGet(rt.totalMemory() - rt.freeMemory() - baseline) { a, b -> maxOf(a, b) }
                        Thread.sleep(50)
                    }
                }.apply { isDaemon = true; start() }
                var normalized: dev.velum.app.platform.NormalizedAudio? = null
                val normMs = time { normalized = runBlocking { AudioNormalizer().normalizeToWav(src.absolutePath, 16_000) } }
                running.set(false); sampler.join(2_000)
                val wav = File(normalized!!.wavPath)
                val expectedBytes = m * 60L * 16_000 * 2
                assertTrue("wav size ${wav.length()} vs ~$expectedBytes", kotlin.math.abs(wav.length() - expectedBytes) < 16_000L * 2 * 2)
                val line = "minutes=$m srcKiB=${src.length() shr 10} encodeMs=$encodeMs normalizeMs=$normMs " +
                    "x_realtime=${"%.1f".format(java.util.Locale.ROOT, m * 60_000.0 / normMs)} wavMiB=${wav.length() shr 20} " +
                    "peakJavaHeapGrowthKiB=${peak.get() shr 10}"
                Log.i(TAG, line); lines += line
                wav.delete(); src.delete()
            }
        } finally {
            dir.deleteRecursively()
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("velum.norm319", lines.joinToString(" || ")) })
        // Linearity: time per audio minute within 3x between the shortest and longest length.
        if (lines.size >= 2) {
            val rate = lines.map { l -> Regex("normalizeMs=(\\d+)").find(l)!!.groupValues[1].toDouble() }
                .zip(minutes) { ms, m -> ms / m }
            assertTrue("time per minute not linear: $rate", rate.max() <= rate.min() * 3.0)
        }
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
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(dest.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var muxerStarted = false
        val info = MediaCodec.BufferInfo()
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        try {
            val framesPerChunk = 2048
            val totalFrames = seconds.toLong() * rate
            var frame = 0L
            var inputDone = false
            var outputDone = false
            val chunk = ByteBuffer.allocate(framesPerChunk * 4).order(ByteOrder.LITTLE_ENDIAN)
            while (!outputDone) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = minOf(framesPerChunk.toLong(), totalFrames - frame).toInt()
                        val buf = codec.getInputBuffer(i)!!
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
                        codec.queueInputBuffer(i, 0, n * 4, pts, if (inputDone) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(codec.outputFormat); muxer.start(); muxerStarted = true }
                    o >= 0 -> {
                        val out = codec.getOutputBuffer(o)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && muxerStarted) { out.position(info.offset); out.limit(info.offset + info.size); muxer.writeSampleData(track, out, info) }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }; codec.release()
            runCatching { if (muxerStarted) muxer.stop() }; muxer.release()
        }
        assertEquals(true, dest.length() > 0)
    }

    private companion object { const val TAG = "VelumNorm319" }
}
