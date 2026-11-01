package dev.velum.app.platform

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class WavRecoveryDataLossTest {
    @Test fun invalid_partial_never_replaces_a_valid_final_wav() = fixture { dir ->
        val final = File(dir, "rec-synthetic.wav"); writeWave(final, 7)
        val partial = File(dir, "rec-synthetic.wav.partial").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val finalBytes = final.readBytes(); val partialBytes = partial.readBytes()
        WavWriter.recoverOrFinalize(partial.path)
        assertContentEquals(finalBytes, final.readBytes())
        assertTrue(partial.exists()); assertContentEquals(partialBytes, partial.readBytes())
    }
    @Test fun different_valid_partial_never_overwrites_a_valid_final_wav() = fixture { dir ->
        val final = File(dir, "rec-synthetic.wav"); writeWave(final, 7)
        val partial = File(dir, "rec-synthetic.wav.partial"); writeWave(partial, 9)
        val finalBytes = final.readBytes(); val partialBytes = partial.readBytes()
        WavWriter.recoverOrFinalize(partial.path)
        assertContentEquals(finalBytes, final.readBytes())
        assertTrue(partial.exists()); assertContentEquals(partialBytes, partial.readBytes())
    }
    private fun writeWave(file: File, sample: Byte) {
        RandomAccessFile(file, "rw").use { raf ->
            writeWavHeaderTo(raf, 32_000, 16_000); raf.seek(44); raf.write(ByteArray(32_000) { sample })
        }
    }
    private fun fixture(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("velum-wav-collision-test").toFile()
        try { block(dir) } finally { dir.deleteRecursively() }
    }
}
