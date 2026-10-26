package dev.velum.app.platform

import java.io.File
import java.io.RandomAccessFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WavWriterRecoverFinalizeTest {
    @Test
    fun recoverOrFinalize_rewritesZeroDataHeader() {
        val dir = createTempDir(prefix = "velum-wav-")
        try {
            val wav = File(dir, "crash.wav")
            RandomAccessFile(wav, "rw").use { raf ->
                writeWavHeaderTo(raf, dataSize = 0, sampleRateHz = 16_000)
                raf.seek(44)
                raf.write(ByteArray(3200) { 1 })
            }
            assertTrue(wav.length() > 44)

            val finished = WavWriter.recoverOrFinalize(wav.absolutePath, sampleRateHz = 16_000)
            assertNotNull(finished)
            assertEquals(wav.absolutePath, finished.absolutePath)
            assertEquals(3200L / (2 * 16_000), finished.durationSeconds)

            val dataSize = RandomAccessFile(wav, "r").use { raf ->
                raf.seek(40)
                val b0 = raf.read()
                val b1 = raf.read()
                val b2 = raf.read()
                val b3 = raf.read()
                b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
            }
            assertEquals(3200, dataSize)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun recoverOrFinalize_renamesPartialAndFixesHeader() {
        val dir = createTempDir(prefix = "velum-wav-partial-")
        try {
            val partial = File(dir, "rec.wav.partial")
            RandomAccessFile(partial, "rw").use { raf ->
                writeWavHeaderTo(raf, dataSize = 0, sampleRateHz = 16_000)
                raf.seek(44)
                raf.write(ByteArray(1600) { 2 })
            }

            val finished = WavWriter.recoverOrFinalize(partial.absolutePath, sampleRateHz = 16_000)
            assertNotNull(finished)
            assertTrue(finished.absolutePath.endsWith("rec.wav"))
            assertTrue(File(finished.absolutePath).isFile)
            assertNull(WavWriter.recoverOrFinalize(File(dir, "missing.wav").absolutePath))
        } finally {
            dir.deleteRecursively()
        }
    }
}
