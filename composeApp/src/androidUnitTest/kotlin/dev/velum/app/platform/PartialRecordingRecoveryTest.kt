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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * #86 QA: download partials cleaned at startup; mic `.wav.partial` still recovers;
 * interrupted download leaves nothing after the next startup.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PartialRecordingRecoveryTest {
    private lateinit var recordings: File
    private lateinit var downloadTmp: File
    private lateinit var repository: VelumRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("velum.db")
        recordings = File(context.cacheDir, "velum-partial-recovery").apply {
            deleteRecursively()
            mkdirs()
        }
        downloadTmp = File(recordings, DownloadedSessionAudioPersist.DOWNLOAD_TEMP_DIR_NAME).apply {
            mkdirs()
        }
        repository = VelumRepository(createDatabase(DriverFactory(context)))
    }

    @Test
    fun isMicRecoveryCandidate_excludesDownloadTemps() {
        assertFalse(isMicRecoveryCandidate("rec-1.wav${DownloadedSessionAudioPersist.TEMP_SUFFIX}"))
        assertFalse(isMicRecoveryCandidate("x.ogg.partial"))
        assertFalse(isMicRecoveryCandidate("x.wav.partial"))
        assertTrue(isMicRecoveryCandidate("rec-1.wav.partial"))
        assertTrue(isMicRecoveryCandidate("rec-1.wav"))
    }

    @Test
    fun qa_startupCleanup_removesOggAndWavPartials_fromBothLocations_noOrphanWav() = runBlocking {
        // Seed in download temp location AND recordings folder.
        File(downloadTmp, "x.ogg.partial").writeBytes(byteArrayOf(1, 2, 3))
        File(downloadTmp, "x.wav.partial").writeBytes(byteArrayOf(4, 5, 6))
        File(recordings, "x.ogg.partial").writeBytes(byteArrayOf(7, 8, 9))
        File(recordings, "x.wav.partial").writeBytes(byteArrayOf(10, 11, 12))

        val removed = cleanupStaleDownloadArtifacts(recordings.absolutePath, repository)
        assertTrue(removed >= 4, "removed=$removed")

        assertFalse(File(downloadTmp, "x.ogg.partial").exists())
        assertFalse(File(downloadTmp, "x.wav.partial").exists())
        assertFalse(File(recordings, "x.ogg.partial").exists())
        assertFalse(File(recordings, "x.wav.partial").exists())
        assertFalse(File(recordings, "x.wav").exists(), "must not create orphan .wav")
        assertNull(repository.getRecording("x"))
    }

    @Test
    fun qa_micPartial_stillRecoveredAndQueued() = runBlocking {
        val id = "rec-1700000000-1234567890"
        val partial = File(recordings, "$id.wav.partial")
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

        // Startup order: download cleanup first, then mic recovery.
        cleanupStaleDownloadArtifacts(recordings.absolutePath, repository)
        val recovered = ArrayList<String>()
        recoverPartialMicRecordings(recordings.absolutePath, repository) { recovered += it }

        assertEquals(listOf(id), recovered)
        assertFalse(partial.exists())
        val finished = File(recordings, "$id.wav")
        assertTrue(finished.isFile)
        val row = repository.getRecording(id)
        assertNotNull(row)
        assertEquals("queued", row.status)
        assertEquals(finished.absolutePath, row.local_path)
    }

    @Test
    fun qa_interruptedDownload_leavesNothingAfterNextStartup() = runBlocking {
        // Simulate process kill mid-persist: abandon temp in .download-tmp, no DB row.
        val abandoned = File(
            downloadTmp,
            "rec-999.ogg${DownloadedSessionAudioPersist.TEMP_SUFFIX}",
        )
        abandoned.writeBytes(
            byteArrayOf('O'.code.toByte(), 'g'.code.toByte(), 'g'.code.toByte(), 'S'.code.toByte(), 1),
        )
        // Also a legacy sibling partial as if an older build crashed in recordings/.
        File(recordings, "rec-999.ogg.partial").writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(abandoned.exists())

        cleanupStaleDownloadArtifacts(recordings.absolutePath, repository)
        recoverPartialMicRecordings(recordings.absolutePath, repository)

        assertFalse(abandoned.exists())
        assertFalse(File(recordings, "rec-999.ogg.partial").exists())
        assertFalse(File(recordings, "rec-999.ogg").exists())
        assertFalse(File(recordings, "rec-999.wav").exists())
        assertNull(repository.getRecording("rec-999"))
    }

    @Test
    fun orphanRecWavPartial_withoutRecordingRow_deletedAtStartup() = runBlocking {
        val orphan = File(recordings, "rec-orphan.wav.partial")
        RandomAccessFile(orphan, "rw").use { raf ->
            writeWavHeaderTo(raf, dataSize = 0, sampleRateHz = 16_000)
            raf.seek(44)
            raf.write(ByteArray(800) { 2 })
        }
        cleanupStaleDownloadArtifacts(recordings.absolutePath, repository)
        recoverPartialMicRecordings(recordings.absolutePath, repository)
        assertFalse(orphan.exists())
        assertFalse(File(recordings, "rec-orphan.wav").exists(), "must not finalize orphan into .wav")
        assertNull(repository.getRecording("rec-orphan"))
    }

    @Test
    fun recordingIdFromWavName_parsesMicNames() {
        assertEquals("rec-x", recordingIdFromWavName("rec-x.wav"))
        assertEquals("rec-x", recordingIdFromWavName("rec-x.wav.partial"))
    }
}
