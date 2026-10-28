package dev.velum.ai.audio

import dev.velum.ai.local.ModelStoreDir
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * #86 AC4 (slower): stream synthetic 10 / 60 / 240 minute Ogg Opus through
 * persist + [KopusOpusDecoder.decodePathToPcmFile] and assert peak heap grows far
 * slower than PCM duration (not proportional to holding the whole PCM).
 *
 * Generates silence Ogg on the fly (not stored in the repo). Prints the three
 * measured peaks so QA/CTO can copy them into the land report.
 */
class HeapVsDurationDecodeTest {
    @Test
    fun peakHeap_240min_boundedVs_10min_andFarBelowFullPcm() {
        val durations = listOf(10, 60, 240)
        val peaks = LinkedHashMap<Int, Long>()

        // Calibrate samples/packet via the same decoder the download path uses
        // (kopus max-frame buffer may emit >20 ms of PCM per silence packet).
        val samplesPerPacket = KopusOpusDecoder()
            .decodeToPcm(SyntheticOggOpus.buildOgg(listOf(SyntheticOggOpus.silencePacket())))
            .samples.size
        assertTrue(samplesPerPacket > 0, "calibration produced empty PCM")
        println("HEAP_VS_DURATION_SAMPLES_PER_PACKET=$samplesPerPacket")

        val root = File.createTempFile("velum-heap-vs-dur", null).apply {
            delete()
            mkdirs()
        }
        try {
            for (minutes in durations) {
                val work = File(root, "${minutes}m").apply { mkdirs() }
                val targetSamples =
                    minutes.toLong() * 60L * SyntheticOggOpus.SAMPLE_RATE_HZ
                val packetCount = targetSamples / samplesPerPacket
                require(packetCount * samplesPerPacket == targetSamples) {
                    "minutes=$minutes not divisible by samplesPerPacket=$samplesPerPacket"
                }
                val expectedPcmBytes = targetSamples * 2L

                val oggFile = File(work, "synth.ogg")
                BufferedOutputStream(FileOutputStream(oggFile), 256 * 1024).use { out ->
                    SyntheticOggOpus.writeSilenceOgg(out, packetCount = packetCount)
                }
                // Persist path: same helper AppContainer uses after SoftAP download decrypt.
                val oggBytes = oggFile.readBytes()
                val persisted = DownloadedSessionAudioPersist.persistToDirectory(
                    decryptedBytes = oggBytes,
                    destDirectory = ModelStoreDir(work.absolutePath),
                    fileBaseName = "session",
                )
                assertTrue(persisted.extension == "ogg")
                assertTrue(File(persisted.absolutePath).exists())

                val pcmSpill = ModelStoreDir(work.absolutePath).resolve("out.s16le")
                val sampler = HeapSampler()
                sampler.start()
                try {
                    val meta = KopusOpusDecoder().decodePathToPcmFile(
                        persisted.absolutePath,
                        pcmSpill,
                    )
                    assertTrue(
                        meta.sizeBytes == expectedPcmBytes,
                        "${minutes}m PCM size ${meta.sizeBytes} != expected $expectedPcmBytes",
                    )
                } finally {
                    sampler.stop()
                }
                peaks[minutes] = sampler.peakUsedBytes
                // Free disk before the next duration (240 m PCM ≈ 1.38 GiB).
                work.deleteRecursively()
                System.gc()
            }
        } finally {
            root.deleteRecursively()
        }

        val peak10 = peaks.getValue(10)
        val peak60 = peaks.getValue(60)
        val peak240 = peaks.getValue(240)
        // Required: print the three measured peaks for the land report.
        println("HEAP_VS_DURATION_PEAKS_BYTES 10m=$peak10 60m=$peak60 240m=$peak240")
        println(
            "HEAP_VS_DURATION_PEAKS_MIB " +
                "10m=${mib(peak10)} 60m=${mib(peak60)} 240m=${mib(peak240)}",
        )

        // 2× the 10‑minute peak plus a fixed margin (short 10 m runs under-sample GC churn).
        val marginBytes = 128L * 1024L * 1024L // 128 MiB fixed margin
        val bound = 2L * peak10 + marginBytes
        assertTrue(
            peak240 <= bound,
            "240m peak $peak240 exceeds 2×10m+margin bound $bound (10m=$peak10)",
        )

        // Full 240 m PCM @ 48 kHz mono s16le ≈ 1.382 GiB — streaming must stay well below.
        val fullPcm240 = 240L * 60L * SyntheticOggOpus.SAMPLE_RATE_HZ * 2L
        assertTrue(
            peak240 < fullPcm240 / 8,
            "240m peak $peak240 not well below full-PCM $fullPcm240",
        )
        // Absolute ceiling: streaming decode must not approach holding even 10 m of PCM.
        val absoluteCeiling = 256L * 1024L * 1024L
        assertTrue(
            peak240 < absoluteCeiling,
            "240m peak $peak240 exceeds absolute streaming ceiling $absoluteCeiling",
        )
        assertTrue(
            peak60 <= bound,
            "60m peak $peak60 exceeds bound $bound (10m=$peak10)",
        )
    }

    private fun mib(bytes: Long): String =
        "%.1f".format(bytes.toDouble() / (1024.0 * 1024.0))

    /**
     * Samples [Runtime] used heap (after opportunistic GC) on a background thread.
     * Peak is the max of (totalMemory − freeMemory) observed during the run.
     */
    private class HeapSampler {
        @Volatile
        private var running = false
        private var thread: Thread? = null
        @Volatile
        var peakUsedBytes: Long = 0L
            private set

        fun start() {
            System.gc()
            peakUsedBytes = usedHeap()
            running = true
            thread = Thread({
                val mx = ManagementFactory.getMemoryMXBean()
                var ticks = 0
                while (running) {
                    // GC every ~2 s so sampling does not dominate wall time; always
                    // record Runtime / MemoryMXBean used-heap between GCs.
                    if (ticks % 40 == 0) System.gc()
                    val viaRuntime = usedHeap()
                    val viaMx = mx.heapMemoryUsage.used
                    val sample = maxOf(viaRuntime, viaMx)
                    if (sample > peakUsedBytes) peakUsedBytes = sample
                    ticks++
                    try {
                        Thread.sleep(50)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }, "heap-vs-duration-sampler").also {
                it.isDaemon = true
                it.start()
            }
        }

        fun stop() {
            running = false
            thread?.join(5_000)
            System.gc()
            val finalSample = maxOf(usedHeap(), ManagementFactory.getMemoryMXBean().heapMemoryUsage.used)
            if (finalSample > peakUsedBytes) peakUsedBytes = finalSample
        }

        private fun usedHeap(): Long {
            val rt = Runtime.getRuntime()
            return rt.totalMemory() - rt.freeMemory()
        }
    }
}
