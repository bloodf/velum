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

    /** Heap retained as of the most recent GC, from the JVM's own pool bookkeeping. */
    private fun heapAfterGc(): Long {
        System.gc()
        return java.lang.management.ManagementFactory.getMemoryPoolMXBeans()
            .filter { it.type == java.lang.management.MemoryType.HEAP }
            .sumOf { it.collectionUsage?.used ?: 0L }
    }

    /**
     * Peak retained heap above the pre-run baseline while [block] runs. Read after a GC from the pools' collection
     * usage, not as `totalMemory - freeMemory`: that second form also counts garbage allocated after the GC, and when
     * the sampler thread is descheduled on a loaded machine the decoder's garbage alone can pass the limit (seen on CI).
     */
    private fun <T> peakRetainedHeap(block: () -> T): Pair<T, Long> {
        val baseline = heapAfterGc()
        val running = AtomicBoolean(true)
        val peak = AtomicLong(0L)
        val sampler = Thread {
            while (running.get()) {
                peak.accumulateAndGet(heapAfterGc() - baseline) { a, b -> maxOf(a, b) }
                Thread.sleep(100)
            }
        }.apply { isDaemon = true; start() }
        val result = try {
            block()
        } finally {
            running.set(false)
            sampler.join(5_000)
        }
        return result to peak.get()
    }

    private fun peakHeapDecoding(seconds: Int): Long {
        val dir = Files.createTempDirectory("velum-86-heap").toFile()
        try {
            val ogg = File(dir, "synth.ogg").also { writeOgg(it, seconds) }
            val (meta, peak) = peakRetainedHeap {
                KopusOpusDecoder().decodePathToPcmFile(ogg.path, ModelStoreDir(File(dir, "out.s16le").path))
            }
            assertEquals(seconds.toLong() * meta.sampleRateHz * meta.channels * 2, meta.sizeBytes, "golden length for $seconds s")
            return peak
        } finally {
            dir.deleteRecursively()
        }
    }

    /** Positive control: the sampler must see a known retained allocation, or a pass would prove nothing. */
    @Test
    fun the_sampler_sees_a_known_retained_allocation() {
        val (_, peak) = peakRetainedHeap {
            val held = ByteArray(24 shl 20) { 1 }
            Thread.sleep(600) // several sampler ticks while the array is reachable
            java.lang.ref.Reference.reachabilityFence(held)
        }
        assertTrue(peak > 16L shl 20, "the sampler saw only ${peak shr 20} MiB of a 24 MiB array")
    }

    @Test
    fun peak_heap_does_not_grow_with_recording_duration() {
        val short = peakHeapDecoding(5 * 60)
        val long = peakHeapDecoding(40 * 60) // ~73 MiB of 16 kHz mono PCM on disk
        println("opus-decode heap delta: 5min=${short shr 10}KiB 40min=${long shr 10}KiB")
        assertTrue(long < 8L shl 20, "40 min decode retained ${long shr 20} MiB (PCM on disk is ~73 MiB)")
        assertTrue(long - short < 8L shl 20, "heap grew with duration: $short -> $long")
    }
}
