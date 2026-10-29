package dev.velum.app.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.velum.app.platform.installRecordingFilesDir
import dev.velum.app.platform.recordingsDirectoryPath
import dev.velum.app.platform.writeBytesAtomically
import dev.velum.app.ui.DownloadedSessionAudio
import dev.velum.core.data.DriverFactory
import dev.velum.core.security.InMemorySecureStore
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Re-downloading the same device session must reuse the library row (#113).
 * Fixtures only — identity values are never printed.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PersistDownloadedSessionDedupeTest {
    private lateinit var filesRoot: File
    private lateinit var container: AppContainer

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("velum.db")
        filesRoot = File(context.cacheDir, "velum-session-dedupe").apply {
            deleteRecursively()
            mkdirs()
        }
        installRecordingFilesDir(filesRoot.absolutePath)
        container = AppContainer(
            secureStore = InMemorySecureStore(),
            driverFactory = DriverFactory(context),
        )
    }

    @Test
    fun sameSessionDownloadedTwice_oneRowOneFile_keepsTranscriptAndSummary() = runBlocking {
        // Re-pin the recordings root — other Robolectric tests share this process global.
        installRecordingFilesDir(filesRoot.absolutePath)
        val firstBytes = wavFixture(1)
        val secondBytes = wavFixture(2)

        val id1 = container.persistDownloadedSession(session(firstBytes))
        container.repository.updateTranscript(id1, FIXTURE_TRANSCRIPT)
        container.repository.updateSummary(id1, FIXTURE_SUMMARY)

        val id2 = container.persistDownloadedSession(session(secondBytes))

        assertEquals(id1, id2, "re-download must reuse the recording id")
        val rows = container.repository.getAllRecordings()
            .filter { it.session_id == FIXTURE_SESSION }
        assertEquals(1, rows.size, "must not create a second library row")
        val rec = rows.single()
        assertEquals(FIXTURE_TRANSCRIPT, rec.transcript)
        assertEquals(FIXTURE_SUMMARY, rec.summary_markdown)
        val path = rec.local_path
        assertNotNull(path)
        assertTrue(path.startsWith(recordingsDirectoryPath()))
        assertTrue(File(path).exists())
        assertTrue(File(path).readBytes().contentEquals(secondBytes))
        val wavFiles = File(recordingsDirectoryPath()).listFiles()
            ?.filter { it.isFile && it.extension == "wav" && it.name.startsWith("rec-") }
            .orEmpty()
        assertEquals(1, wavFiles.size, "must not leave an orphan wav from the first download")
    }

    @Test
    fun failedRedownload_leavesExistingRecordingUntouched() = runBlocking {
        installRecordingFilesDir(filesRoot.absolutePath)
        val original = wavFixture(3)
        val id = container.persistDownloadedSession(session(original))
        container.repository.updateTranscript(id, FIXTURE_TRANSCRIPT)
        val pathBefore = container.repository.getRecording(id)?.local_path
        assertNotNull(pathBefore)
        val bytesBefore = File(pathBefore).readBytes()

        assertFails {
            container.persistDownloadedSession(
                session(wavFixture(4)),
                writeAudio = { path, bytes ->
                    writeBytesAtomically(path, bytes) {
                        error("injected failure before rename")
                    }
                },
            )
        }

        val rec = container.repository.getRecording(id)
        assertNotNull(rec)
        assertEquals(FIXTURE_TRANSCRIPT, rec.transcript)
        assertEquals(pathBefore, rec.local_path)
        assertTrue(File(pathBefore).readBytes().contentEquals(bytesBefore))
        assertEquals(1, container.repository.getAllRecordings().filter { it.session_id == FIXTURE_SESSION }.size)
        val partials = File(recordingsDirectoryPath()).listFiles()
            ?.filter { it.name.endsWith(".download.partial") }
            .orEmpty()
        assertTrue(partials.isEmpty(), "failed re-download must not leave partial temps")
        assertFalse(File(pathBefore + ".download.partial").exists())
    }

    @Test
    fun redownloadOfTrashedSession_restoresSameRowAndRefreshesAudio() = runBlocking {
        installRecordingFilesDir(filesRoot.absolutePath)
        val original = wavFixture(5)
        val refreshed = wavFixture(6)
        val id = container.persistDownloadedSession(session(original))
        container.repository.updateTranscript(id, FIXTURE_TRANSCRIPT)
        container.repository.trashRecording(id)
        assertEquals(1L, container.repository.getRecording(id)?.trashed)

        val id2 = container.persistDownloadedSession(session(refreshed))

        assertEquals(id, id2, "trashed session must restore the same recording id")
        val rec = container.repository.getRecording(id)
        assertNotNull(rec)
        assertEquals(0L, rec.trashed, "re-download must restore from trash")
        assertEquals(FIXTURE_TRANSCRIPT, rec.transcript)
        assertTrue(File(rec.local_path!!).readBytes().contentEquals(refreshed))
        assertEquals(1, container.repository.getAllRecordings().count { it.session_id == FIXTURE_SESSION })
    }

    private fun session(bytes: ByteArray) = DownloadedSessionAudio(
        sessionId = FIXTURE_SESSION,
        deviceSerial = "fixture-serial",
        startedAtEpochSeconds = 1_700_000_000L,
        sizeBytes = bytes.size.toLong(),
        decryptedBytes = bytes,
    )

    private fun wavFixture(seed: Byte): ByteArray = byteArrayOf(
        'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
        0, 0, 0, 0,
        'W'.code.toByte(), 'A'.code.toByte(), 'V'.code.toByte(), 'E'.code.toByte(),
        seed, seed, seed, seed,
    )

    companion object {
        private const val FIXTURE_SESSION = 0x2222_0001L
        private const val FIXTURE_TRANSCRIPT = "kept transcript"
        private const val FIXTURE_SUMMARY = "## kept summary"
    }
}
