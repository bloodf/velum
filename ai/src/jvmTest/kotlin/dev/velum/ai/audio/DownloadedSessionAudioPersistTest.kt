package dev.velum.ai.audio

import dev.velum.ai.local.ModelStoreDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadedSessionAudioPersistTest {
    @Test
    fun persist_ogg_passthrough_tempGone_destComplete() {
        withTempDir { dir ->
            val ogg = byteArrayOf(
                'O'.code.toByte(), 'g'.code.toByte(), 'g'.code.toByte(), 'S'.code.toByte(),
                1, 2, 3, 4,
            )
            val written = DownloadedSessionAudioPersist.persistToDirectory(
                decryptedBytes = ogg,
                destDirectory = ModelStoreDir(dir.absolutePath),
                fileBaseName = "sess",
            )
            assertEquals("ogg", written.extension)
            assertTrue(File(written.absolutePath).exists())
            assertTrue(File(written.absolutePath).readBytes().contentEquals(ogg))
            assertFalse(downloadTempFile(dir, "sess.ogg").exists())
            assertEquals(ogg.size.toLong(), written.sizeBytes)
        }
    }

    @Test
    fun persist_wav_passthrough_tempGone() {
        withTempDir { dir ->
            val wav = PcmWav.encode(ByteArray(32) { 1 }, sampleRateHz = 16_000)
            val written = DownloadedSessionAudioPersist.persistToDirectory(
                decryptedBytes = wav,
                destDirectory = ModelStoreDir(dir.absolutePath),
                fileBaseName = "sess",
            )
            assertEquals("wav", written.extension)
            assertTrue(File(written.absolutePath).readBytes().contentEquals(wav))
            assertFalse(downloadTempFile(dir, "sess.wav").exists())
        }
    }

    @Test
    fun persist_opaquePcm_writesFullWavByteForByte_andLogsWithoutIdentity() {
        withTempDir { dir ->
            val pcm = ByteArray(64) { (it % 40).toByte() }
            val expected = PcmWav.encode(pcm, sampleRateHz = 16_000, channels = 1)
            val logs = ArrayList<String>()
            val written = DownloadedSessionAudioPersist.persistToDirectory(
                decryptedBytes = pcm,
                destDirectory = ModelStoreDir(dir.absolutePath),
                fileBaseName = "sess",
                sampleRateHz = 16_000,
                channels = 1,
                logInfo = { logs += it },
            )
            assertEquals("wav", written.extension)
            assertFalse(downloadTempFile(dir, "sess.wav").exists())
            val out = File(written.absolutePath).readBytes()
            assertTrue(
                out.contentEquals(expected),
                "written WAV must equal PcmWav.encode (len=${out.size} expected=${expected.size})",
            )
            assertEquals(expected.size.toLong(), written.sizeBytes)
            assertEquals(1, logs.size)
            assertTrue(logs[0].contains("sizeBytes=${pcm.size}"))
            assertTrue(logs[0].contains("sniff=none"))
            assertFalse(logs[0].contains("serial", ignoreCase = true))
            assertFalse(logs[0].contains("MAC", ignoreCase = true))
            assertFalse(logs[0].contains("sessionId", ignoreCase = true))
            assertFalse(logs[0].contains("device", ignoreCase = true))
            assertFalse(Regex("(?i)\\bkey[s]?\\b").containsMatchIn(logs[0]))
        }
    }

    @Test
    fun writeAtomically_failure_deletesTemp_andLeavesDestAbsent() {
        withTempDir { dir ->
            val dest = ModelStoreDir(dir.absolutePath).resolve("out.wav")
            assertFailsWith<IllegalStateException> {
                DownloadedSessionAudioPersist.writeAtomically(dest) { temp ->
                    temp.writeBytes(byteArrayOf(1, 2, 3, 4))
                    assertTrue(temp.exists())
                    error("simulated write failure")
                }
            }
            assertFalse(dest.exists(), "destination must not exist after failed write")
            assertFalse(downloadTempFile(dir, "out.wav").exists(), "temp must be cleaned up")
        }
    }

    @Test
    fun writeAtomically_failedRename_deletesTemp_leavesDestUntouched() {
        withTempDir { dir ->
            val dest = ModelStoreDir(dir.absolutePath).resolve("out.wav")
            val prior = byteArrayOf(9, 9, 9)
            dest.writeBytes(prior)
            assertFailsWith<IllegalStateException> {
                DownloadedSessionAudioPersist.writeAtomically(
                    dest = dest,
                    rename = { _, _ -> false },
                ) { temp ->
                    temp.writeBytes(byteArrayOf(1, 2, 3, 4))
                }
            }
            assertTrue(dest.exists())
            assertTrue(dest.readBytes().contentEquals(prior), "existing dest must stay intact")
            assertFalse(downloadTempFile(dir, "out.wav").exists(), "failed rename must still delete temp")
        }
    }

    @Test
    fun writeAtomically_replacesExistingDestination() {
        withTempDir { dir ->
            val dest = ModelStoreDir(dir.absolutePath).resolve("out.ogg")
            dest.writeBytes(byteArrayOf(1, 1, 1))
            val payload = byteArrayOf(7, 7, 7, 7)
            DownloadedSessionAudioPersist.writeAtomically(dest) { temp ->
                temp.writeBytes(payload)
            }
            assertTrue(dest.readBytes().contentEquals(payload))
            assertFalse(downloadTempFile(dir, "out.ogg").exists())
        }
    }

    @Test
    fun writeAtomically_success_removesTemp() {
        withTempDir { dir ->
            val dest = ModelStoreDir(dir.absolutePath).resolve("out.bin")
            val payload = ByteArray(8) { 9 }
            DownloadedSessionAudioPersist.writeAtomically(dest) { temp ->
                temp.writeBytes(payload)
            }
            assertTrue(dest.exists())
            assertTrue(dest.readBytes().contentEquals(payload))
            assertFalse(downloadTempFile(dir, "out.bin").exists())
        }
    }

    @Test
    fun deleteStaleDownloadTemps_clearsDownloadTmpAndLegacyPartials_notMicRecPartial() {
        withTempDir { dir ->
            val store = ModelStoreDir(dir.absolutePath)
            val tmp = File(dir, DownloadedSessionAudioPersist.DOWNLOAD_TEMP_DIR_NAME).apply { mkdirs() }
            File(tmp, "sess.ogg${DownloadedSessionAudioPersist.TEMP_SUFFIX}").writeBytes(byteArrayOf(1))
            File(tmp, "sess.wav${DownloadedSessionAudioPersist.TEMP_SUFFIX}").writeBytes(byteArrayOf(2))
            File(dir, "x.ogg.partial").writeBytes(byteArrayOf(3))
            File(dir, "x.wav.partial").writeBytes(byteArrayOf(4))
            File(dir, "legacy.ogg.partial").writeBytes(byteArrayOf(5))
            val micPartial = File(dir, "rec-mic.wav.partial").apply { writeBytes(byteArrayOf(6)) }
            val keepWav = File(dir, "rec-mic.wav").apply { writeBytes(byteArrayOf(7)) }

            val removed = DownloadedSessionAudioPersist.deleteStaleDownloadTemps(store)
            assertTrue(removed >= 5, "removed=$removed")
            assertFalse(File(tmp, "sess.ogg${DownloadedSessionAudioPersist.TEMP_SUFFIX}").exists())
            assertFalse(File(tmp, "sess.wav${DownloadedSessionAudioPersist.TEMP_SUFFIX}").exists())
            assertFalse(File(dir, "x.ogg.partial").exists())
            assertFalse(File(dir, "x.wav.partial").exists())
            assertFalse(File(dir, "legacy.ogg.partial").exists())
            assertTrue(micPartial.exists(), "mic rec-*.wav.partial must survive download cleanup")
            assertTrue(keepWav.exists())
        }
    }

    @Test
    fun persist_rejectsNonPositiveChunkBytes() {
        withTempDir { dir ->
            assertFailsWith<IllegalArgumentException> {
                DownloadedSessionAudioPersist.persistToDirectory(
                    decryptedBytes = byteArrayOf(1, 2, 3, 4),
                    destDirectory = ModelStoreDir(dir.absolutePath),
                    fileBaseName = "sess",
                    chunkBytes = 0,
                )
            }
        }
    }

    @Test
    fun detectExtension_neverBin() {
        assertEquals(
            "ogg",
            DownloadedSessionAudioPersist.detectExtension(
                byteArrayOf('O'.code.toByte(), 'g'.code.toByte(), 'g'.code.toByte(), 'S'.code.toByte()),
            ),
        )
        assertEquals(
            "wav",
            DownloadedSessionAudioPersist.detectExtension(PcmWav.encode(ByteArray(4), 16_000)),
        )
        assertEquals(
            "wav",
            DownloadedSessionAudioPersist.detectExtension(ByteArray(32) { 0x55 }),
        )
    }

    private fun downloadTempFile(recordingsDir: File, destName: String): File =
        File(
            File(recordingsDir, DownloadedSessionAudioPersist.DOWNLOAD_TEMP_DIR_NAME),
            "$destName${DownloadedSessionAudioPersist.TEMP_SUFFIX}",
        )

    private fun withTempDir(block: (File) -> Unit) {
        val dir = File.createTempFile("velum-persist", null).apply {
            delete()
            mkdirs()
        }
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }
}
