package dev.velum.ai.pipeline

import dev.velum.ai.local.ModelStoreDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertIs

class RecordingProcessorBudgetTest {
    @Test
    fun inMemoryBudget_matchesSpillThreshold() {
        assertEquals(PcmAudioBuffer.IN_MEMORY_BUDGET_BYTES, RecordingProcessor.MAX_AUDIO_BYTES)
        assertTrue(RecordingProcessor.MAX_AUDIO_BYTES < 200_000_000L)
    }

    @Test
    fun largePcm_spillsToTempFile_andSlicesWithoutFullReload() {
        val dir = File.createTempFile("velum-pcm", null).apply {
            delete()
            mkdirs()
        }
        try {
            val spill = ModelStoreDir(dir.absolutePath)
            // Just over the test budget (8 KiB) so we exercise Spilled without huge allocs.
            val pcm = ByteArray(9 * 1024) { (it % 251).toByte() }
            val buf = PcmAudioBuffer.fromPcmBytes(pcm, spill, inMemoryBudget = 8 * 1024L)
            assertIs<PcmAudioBuffer.Spilled>(buf)
            assertEquals(pcm.size.toLong(), buf.sizeBytes)
            assertEquals(null, buf.asFullArrayOrNull())
            val slice = buf.slice(100, 164)
            assertEquals(64, slice.size)
            assertEquals(pcm[100], slice[0])
            assertEquals(pcm[163], slice[63])
            buf.close()
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun smallPcm_staysInMemory() {
        val dir = File.createTempFile("velum-pcm-sm", null).apply {
            delete()
            mkdirs()
        }
        try {
            val pcm = ByteArray(1024) { 1 }
            val buf = PcmAudioBuffer.fromPcmBytes(
                pcm,
                ModelStoreDir(dir.absolutePath),
                inMemoryBudget = 8 * 1024L,
            )
            assertIs<PcmAudioBuffer.Memory>(buf)
            assertEquals(pcm, buf.asFullArrayOrNull())
        } finally {
            dir.deleteRecursively()
        }
    }
}
