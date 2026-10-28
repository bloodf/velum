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
            assertFalse(File("${written.absolutePath}.partial").exists())
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
            assertFalse(File("${written.absolutePath}.partial").exists())
        }
    }

    @Test
    fun persist_opaquePcm_streamsWavHeaderFields_andLogsWithoutIdentity() {
        withTempDir { dir ->
            val pcm = ByteArray(64) { (it % 40).toByte() }
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
            assertFalse(File("${written.absolutePath}.partial").exists())
            val out = File(written.absolutePath).readBytes()
            assertTrue(PcmWav.looksLikeWav(out))
            assertEquals(44 + pcm.size, out.size)
            assertEquals(36 + pcm.size, u32le(out, 4))
            assertEquals(1, u16le(out, 20)) // PCM format
            assertEquals(1, u16le(out, 22)) // mono
            assertEquals(16_000, u32le(out, 24))
            assertEquals(32_000, u32le(out, 28)) // byte rate
            assertEquals(2, u16le(out, 32)) // block align
            assertEquals(16, u16le(out, 34)) // bits
            assertEquals(pcm.size, u32le(out, 40))
            assertTrue(out.copyOfRange(44, out.size).contentEquals(pcm))
            assertEquals(1, logs.size)
            assertTrue(logs[0].contains("sizeBytes=${pcm.size}"))
            assertTrue(logs[0].contains("sniff=none"))
            // No identity values — only size / sniff / rate / channels.
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
            val tempPath = "${dest.absolutePath}.partial"
            assertFailsWith<IllegalStateException> {
                DownloadedSessionAudioPersist.writeAtomically(dest) { temp ->
                    temp.writeBytes(byteArrayOf(1, 2, 3, 4))
                    assertTrue(temp.exists())
                    error("simulated write failure")
                }
            }
            assertFalse(dest.exists(), "destination must not exist after failed write")
            assertFalse(File(tempPath).exists(), "temp must be cleaned up")
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
            assertFalse(File("${dest.absolutePath}.partial").exists())
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

    private fun u32le(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xff) or
            ((bytes[at + 1].toInt() and 0xff) shl 8) or
            ((bytes[at + 2].toInt() and 0xff) shl 16) or
            ((bytes[at + 3].toInt() and 0xff) shl 24)

    private fun u16le(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xff) or ((bytes[at + 1].toInt() and 0xff) shl 8)
}
