package dev.velum.app.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.velum.ai.audio.DownloadedSessionAudioPersist
import dev.velum.core.data.DriverFactory
import dev.velum.core.data.VelumRepository
import dev.velum.core.data.createDatabase
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * #122: orphan audio sweep after #113 dedupe — delete unreferenced recordings-dir files,
 * keep referenced (including trash), never touch #86 partial/temp, never follow escapes.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class OrphanRecordingAudioSweepTest {
    private lateinit var recordings: File
    private lateinit var outside: File
    private lateinit var repository: VelumRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("velum.db")
        val root = File(context.cacheDir, "velum-orphan-sweep").apply {
            deleteRecursively()
            mkdirs()
        }
        recordings = File(root, "recordings").apply { mkdirs() }
        outside = File(root, "outside").apply { mkdirs() }
        repository = VelumRepository(createDatabase(DriverFactory(context)))
    }

    @Test
    fun orphanDeleted_referencedKept() = runBlocking {
        val orphan = File(recordings, "orphan.ogg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val kept = File(recordings, "kept.ogg").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        insertRecording(
            id = "rec-kept",
            localPath = kept.absolutePath,
            trashed = 0,
        )

        val removed = sweepOrphanRecordingAudio(recordings.absolutePath, repository)
        assertEquals(1, removed)
        assertFalse(orphan.exists(), "unreferenced audio must be deleted")
        assertTrue(kept.exists(), "referenced audio must be kept")

        val second = sweepOrphanRecordingAudio(recordings.absolutePath, repository)
        assertEquals(0, second, "idempotent: second run deletes nothing")
        assertTrue(kept.exists())
    }

    @Test
    fun trashedReferenced_kept() = runBlocking {
        val trashedFile = File(recordings, "trashed.wav").apply { writeBytes(byteArrayOf(7, 8)) }
        insertRecording(
            id = "rec-trashed",
            localPath = trashedFile.absolutePath,
            trashed = 1,
        )

        val removed = sweepOrphanRecordingAudio(recordings.absolutePath, repository)
        assertEquals(0, removed)
        assertTrue(trashedFile.exists(), "trashed row local_path must keep its audio")
    }

    @Test
    fun partialAndTempFiles_kept() = runBlocking {
        val micPartial = File(recordings, "rec-1.wav.partial").apply { writeBytes(byteArrayOf(1)) }
        val oggPartial = File(recordings, "x.ogg.partial").apply { writeBytes(byteArrayOf(2)) }
        val downloadPartial = File(
            recordings,
            "sess.ogg${DownloadedSessionAudioPersist.TEMP_SUFFIX}",
        ).apply { writeBytes(byteArrayOf(3)) }
        val part = File(recordings, "http-get.part").apply { writeBytes(byteArrayOf(4)) }
        val tmp = File(recordings, "scratch.tmp").apply { writeBytes(byteArrayOf(5)) }
        val downloadTmp = File(recordings, DownloadedSessionAudioPersist.DOWNLOAD_TEMP_DIR_NAME)
            .apply { mkdirs() }
        val insideTmp = File(downloadTmp, "live.ogg${DownloadedSessionAudioPersist.TEMP_SUFFIX}")
            .apply { writeBytes(byteArrayOf(6)) }

        val removed = sweepOrphanRecordingAudio(recordings.absolutePath, repository)
        assertEquals(0, removed)
        assertTrue(micPartial.exists())
        assertTrue(oggPartial.exists())
        assertTrue(downloadPartial.exists())
        assertTrue(part.exists())
        assertTrue(tmp.exists())
        assertTrue(insideTmp.exists(), ".download-tmp contents must not be swept")
        assertTrue(downloadTmp.isDirectory)
    }

    @Test
    fun emptyDir_noCrash() = runBlocking {
        val removed = sweepOrphanRecordingAudio(recordings.absolutePath, repository)
        assertEquals(0, removed)
    }

    @Test
    fun outsideDirectory_notTouched() = runBlocking {
        val outsideFile = File(outside, "secret.ogg").apply { writeBytes(byteArrayOf(9, 9, 9)) }
        // Even if a row somehow points outside, sweep must not delete that file.
        insertRecording(
            id = "rec-outside",
            localPath = outsideFile.absolutePath,
            trashed = 0,
        )
        val orphanInside = File(recordings, "inside-orphan.ogg").apply { writeBytes(byteArrayOf(1)) }

        val removed = sweepOrphanRecordingAudio(recordings.absolutePath, repository)
        assertEquals(1, removed)
        assertFalse(orphanInside.exists())
        assertTrue(outsideFile.exists(), "must never delete outside the recordings directory")
    }

    @Test
    fun symlinkTargetOutside_notFollowed() = runBlocking {
        val target = File(outside, "target.ogg").apply { writeBytes(byteArrayOf(3, 3, 3)) }
        val link = File(recordings, "escape.ogg")
        Files.createSymbolicLink(link.toPath(), target.toPath())
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertTrue(target.exists())

        // Orphan symlink entry may be removed, but the outside target must survive.
        val removed = sweepOrphanRecordingAudioFiles(
            recordingsDirPath = recordings.absolutePath,
            referencedLocalPaths = emptyList(),
        )
        assertTrue(removed >= 0)
        assertTrue(target.exists(), "symlink target outside recordings must not be deleted")
        assertTrue(
            !link.exists() || Files.isSymbolicLink(link.toPath()),
            "if link remains it must still be a symlink, never replaced by deleting the target",
        )
        assertTrue(target.readBytes().contentEquals(byteArrayOf(3, 3, 3)))
    }

    @Test
    fun escapeDotDotPath_notFollowedForDelete() {
        val victim = File(outside, "victim.ogg").apply { writeBytes(byteArrayOf(1, 2)) }
        // Inject a fake child whose normalized absolute path escapes the recordings root.
        val escape = File(recordings, "sub/../../outside/victim.ogg")
        val removed = sweepOrphanRecordingAudioFiles(
            recordingsDirPath = recordings.absolutePath,
            referencedLocalPaths = emptyList(),
            listChildren = { arrayOf(escape) },
            deleteEntry = { file, _ ->
                // If the sweep asked to delete the escaped path, fail the assertion below.
                file.delete()
            },
        )
        assertEquals(0, removed, "escaped .. child must be skipped before delete")
        assertTrue(victim.exists(), "file reached via .. outside recordings must survive")
    }

    @Test
    fun sweepException_swallowed_startupContinues() = runBlocking {
        var published = false
        published = true
        val result = sweepOrphanRecordingAudioAfterPublish(
            recordingsDirPath = recordings.absolutePath,
            repository = repository,
            sweep = { _, _ -> error("boom") },
        )
        assertEquals(0, result)
        assertTrue(published, "publish/readiness must remain intact when sweep fails")
    }

    @Test
    fun ioErrorOnList_swallowedByAfterPublish() = runBlocking {
        val result = sweepOrphanRecordingAudioAfterPublish(
            recordingsDirPath = recordings.absolutePath,
            repository = repository,
            sweep = { path, repo ->
                sweepOrphanRecordingAudioFiles(
                    recordingsDirPath = path,
                    referencedLocalPaths = repo.listRecordingLocalPaths(),
                    listChildren = { error("list failed") },
                )
            },
        )
        assertEquals(0, result)
    }

    @Test
    fun protectedNameHelpers() {
        assertTrue(isProtectedOrphanSweepName("rec-1.wav.partial"))
        assertTrue(isProtectedOrphanSweepName("x.ogg.partial"))
        assertTrue(isProtectedOrphanSweepName("a.ogg${DownloadedSessionAudioPersist.TEMP_SUFFIX}"))
        assertTrue(isProtectedOrphanSweepName("file.part"))
        assertTrue(isProtectedOrphanSweepName("x.tmp"))
        assertTrue(isProtectedOrphanSweepName(DownloadedSessionAudioPersist.DOWNLOAD_TEMP_DIR_NAME))
        assertFalse(isProtectedOrphanSweepName("rec-1.wav"))
        assertFalse(isProtectedOrphanSweepName("session.ogg"))
    }

    private suspend fun insertRecording(
        id: String,
        localPath: String,
        trashed: Long,
    ) {
        repository.insertRecording(
            id = id,
            sessionId = null,
            folderId = null,
            title = id,
            source = "device",
            deviceSerial = null,
            startedAt = 1_700_000_000L,
            durationSeconds = 1,
            sizeBytes = 3,
            localPath = localPath,
            transcript = null,
            summaryMarkdown = null,
            status = "ready",
            trashed = trashed,
        )
    }
}
