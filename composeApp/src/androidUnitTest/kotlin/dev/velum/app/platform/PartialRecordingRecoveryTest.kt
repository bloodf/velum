package dev.velum.app.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.velum.ai.audio.DownloadedSessionAudioPersist
import dev.velum.ai.local.ModelStoreDir
import dev.velum.core.data.DriverFactory
import dev.velum.core.data.VelumRepository
import dev.velum.core.data.createDatabase
import java.io.File
import java.io.RandomAccessFile
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * #86: crashed download temps must not be promoted to mic WAVs; real mic `.partial` still recovers.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PartialRecordingRecoveryTest {
    private lateinit var dir: File
    private lateinit var repository: VelumRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("velum.db")
        dir = File(context.cacheDir, "velum-partial-recovery").apply {
            deleteRecursively()
            mkdirs()
        }
        repository = VelumRepository(createDatabase(DriverFactory(context)))
    }

    @Test
    fun isMicRecoveryCandidate_excludesDownloadTemps() {
        assertFalse(isMicRecoveryCandidate("rec-1.wav${DownloadedSessionAudioPersist.TEMP_SUFFIX}"))
        assertFalse(isMicRecoveryCandidate("rec-1.ogg${DownloadedSessionAudioPersist.TEMP_SUFFIX}"))
        assertFalse(isMicRecoveryCandidate("sess.ogg.partial"))
        assertTrue(isMicRecoveryCandidate("rec-1.wav.partial"))
        assertTrue(isMicRecoveryCandidate("rec-1.wav"))
    }

    @Test
    fun startupCleanup_deletesCrashedDownloadTemp_neverCreatesWav() = runBlocking {
        val downloadTemp = File(dir, "rec-download.wav${DownloadedSessionAudioPersist.TEMP_SUFFIX}")
        RandomAccessFile(downloadTemp, "rw").use { raf ->
            writeWavHeaderTo(raf, dataSize = 0, sampleRateHz = 16_000)
            raf.seek(44)
            raf.write(ByteArray(1600) { 9 })
        }
        assertTrue(downloadTemp.exists())

        // Mic recovery must ignore the download suffix even before cleanup.
        recoverPartialMicRecordings(dir.absolutePath, repository)
        assertTrue(downloadTemp.exists(), "download temp must remain after mic recovery")
        assertFalse(
            File(dir, "rec-download.wav").exists(),
            "must not finalize download temp into .wav",
        )

        val removed = DownloadedSessionAudioPersist.deleteStaleDownloadTemps(
            ModelStoreDir(dir.absolutePath),
        )
        assertEquals(1, removed)
        assertFalse(downloadTemp.exists())
        assertFalse(File(dir, "rec-download.wav").exists())
    }

    @Test
    fun micPartial_stillRecoveredAndQueued() = runBlocking {
        val id = "rec-1700000000-1234567890"
        val partial = File(dir, "$id.wav.partial")
        RandomAccessFile(partial, "rw").use { raf ->
            writeWavHeaderTo(raf, dataSize = 0, sampleRateHz = 16_000)
            raf.seek(44)
            raf.write(ByteArray(3200) { 1 })
        }
        repository.insertRecording(
            id = id,
            sessionId = null,
            folderId = null,
            title = "mic",
            source = "mic",
            deviceSerial = null,
            startedAt = 1_700_000_000L,
            durationSeconds = 0,
            sizeBytes = 0,
            localPath = partial.absolutePath,
            transcript = null,
            summaryMarkdown = null,
            status = "recording",
        )

        val recovered = ArrayList<String>()
        recoverPartialMicRecordings(dir.absolutePath, repository) { recovered += it }

        assertEquals(listOf(id), recovered)
        assertFalse(partial.exists())
        val finished = File(dir, "$id.wav")
        assertTrue(finished.isFile)
        val row = repository.getRecording(id)
        assertNotNull(row)
        assertEquals("queued", row.status)
        assertEquals(finished.absolutePath, row.local_path)
    }

    @Test
    fun recordingIdFromWavName_parsesMicNames() {
        assertEquals("rec-x", recordingIdFromWavName("rec-x.wav"))
        assertEquals("rec-x", recordingIdFromWavName("rec-x.wav.partial"))
    }
}
