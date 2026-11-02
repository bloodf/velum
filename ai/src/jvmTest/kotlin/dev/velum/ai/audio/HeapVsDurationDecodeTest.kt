package dev.velum.ai.audio

import dev.velum.ai.local.ModelStoreDir
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #86: streaming Opus decode from disk keeps peak heap flat as duration grows (5 vs 40 min).
 * The Ogg is written to disk page by page and never exists whole on the heap.
 * Adapted from cursor/dev2-86-stream-decode HeapVsDurationDecodeTest (shorter durations).
 */
class HeapVsDurationDecodeTest {
    private val packet = SyntheticOgg.audioPacket(stereo = false) // 20 ms @ 48 kHz

    private fun writeOgg(file: File, seconds: Int) {
        BufferedOutputStream(FileOutputStream(file), 256 * 1024).use { out ->
            out.write(SyntheticOgg.page(listOf(SyntheticOgg.opusHead(1, 48_000)), 0, 2))
            out.write(SyntheticOgg.page(listOf(SyntheticOgg.tags()), 1))
            val second = List(50) { packet }
            repeat(seconds) { out.write(SyntheticOgg.page(second, 2 + it)) }
        }
    }

    private fun peakHeapDecoding(seconds: Int): Long {
        val dir = Files.createTempDirectory("velum-86-heap").toFile()
        try {
            val ogg = File(dir, "synth.ogg").also { writeOgg(it, seconds) }
            val rt = Runtime.getRuntime()
            System.gc()
            val baseline = rt.totalMemory() - rt.freeMemory()
            val running = AtomicBoolean(true)
            val peak = AtomicLong(0L)
            val sampler = Thread {
                // Sample only right after a GC so the peak is retained memory, not uncollected garbage.
                while (running.get()) {
                    System.gc()
                    peak.accumulateAndGet(rt.totalMemory() - rt.freeMemory() - baseline) { a, b -> maxOf(a, b) }
                    Thread.sleep(100)
                }
            }.apply { isDaemon = true; start() }
            val meta = try {
                KopusOpusDecoder().decodePathToPcmFile(ogg.path, ModelStoreDir(File(dir, "out.s16le").path))
            } finally {
                running.set(false)
                sampler.join(5_000)
            }
            assertEquals(seconds.toLong() * meta.sampleRateHz * meta.channels * 2, meta.sizeBytes, "golden length for $seconds s")
            return peak.get()
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun peak_heap_does_not_grow_with_recording_duration() {
        val short = peakHeapDecoding(5 * 60)
        val long = peakHeapDecoding(40 * 60) // ~73 MiB of 16 kHz mono PCM on disk
        println("opus-decode heap delta: 5min=${short shr 10}KiB 40min=${long shr 10}KiB")
        assertTrue(long < 32L shl 20, "40 min decode retained ${long shr 20} MiB (PCM on disk is ~73 MiB)")
        assertTrue(long - short < 32L shl 20, "heap grew with duration: $short -> $long")
    }
}
