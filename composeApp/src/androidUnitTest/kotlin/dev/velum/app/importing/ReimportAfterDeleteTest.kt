package dev.velum.app.importing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.velum.ai.local.ModelStoreDir
import dev.velum.ai.pipeline.RecordingJobScheduler
import dev.velum.app.di.AppContainer
import dev.velum.app.di.WipeInProgressException
import dev.velum.app.platform.installAppContext
import dev.velum.app.platform.installRecordingFilesDir
import dev.velum.app.platform.isAppOwnedAudioPath
import dev.velum.app.platform.isImportCacheCopy
import dev.velum.app.platform.recordingsDirectoryPath
import dev.velum.app.platform.AudioNormalizer
import dev.velum.app.platform.NormalizedAudio
import dev.velum.app.platform.sha256File
import dev.velum.core.data.DriverFactory
import dev.velum.core.security.InMemorySecureStore
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import dev.velum.app.ui.DownloadedSessionAudio
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** #327: a file whose recording is gone can be imported again, instead of pointing at a missing recording. */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class, sdk = [34])
class ReimportAfterDeleteTest {
    private fun withPipeline(onEnqueue: () -> Unit = {}, block: suspend (AppContainer, AudioImportPipeline, File) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("velum.db")
        installAppContext(context)
        val files = Files.createTempDirectory("velum-reimport-files").toFile()
        installRecordingFilesDir(files.path)
        File(context.cacheDir, "imports").deleteRecursively()
        val root = ModelStoreDir(Files.createTempDirectory("velum-reimport").toString())
        // No processing: it is not under test and would outlive the database.
        val container = AppContainer(
            InMemorySecureStore(), DriverFactory(context), modelStoreRoot = root,
            recordingJobSchedulerFactory = { _, _ -> RecordingJobScheduler { _, _, _, _ -> onEnqueue(); dev.velum.ai.pipeline.EnqueueResult.STARTED_OR_REPLACED } },
        )
        val dir = File(context.cacheDir, "reimport-fixtures").apply { deleteRecursively(); mkdirs() }
        try {
            runBlocking { block(container, AudioImportPipeline(container), silentWav(File(dir, "synthetic.wav"))) }
        } finally {
            container.databaseHandle.driver.close()
            root.deleteRecursively()
            dir.deleteRecursively()
            installRecordingFilesDir(null)
            files.deleteRecursively()
        }
    }

    @Test fun a_file_can_be_imported_again_after_its_recording_was_deleted() = withPipeline { container, pipeline, wav ->
        val first = assertIs<ImportResult.Created>(pipeline.importFile(wav.path))
        container.repository.deleteRecordingHard(first.recordingId)
        val again = assertIs<ImportResult.Created>(pipeline.importFile(wav.path))
        assertNotEquals(first.recordingId, again.recordingId)
    }

    @Test fun a_file_can_be_imported_again_after_a_wipe() = withPipeline { container, pipeline, wav ->
        assertIs<ImportResult.Created>(pipeline.importFile(wav.path))
        container.repository.wipeAllUserData()
        assertIs<ImportResult.Created>(pipeline.importFile(wav.path))
    }

    @Test fun a_watched_folder_does_not_bring_back_a_deleted_recording() = withPipeline { container, pipeline, wav ->
        val first = assertIs<ImportResult.Created>(pipeline.importFile(wav.path, source = "watched"))
        container.repository.deleteRecordingHard(first.recordingId)
        assertIs<ImportResult.Deleted>(pipeline.importFile(wav.path, source = "watched"))
        assertEquals(0, container.repository.storageUsage().recordingCount, "the deleted recording came back")
    }

    @Test fun a_manual_import_of_a_deleted_file_works_and_clears_its_tombstone() = withPipeline { container, pipeline, wav ->
        val first = assertIs<ImportResult.Created>(pipeline.importFile(wav.path, source = "watched"))
        container.repository.deleteRecordingHard(first.recordingId)
        val again = assertIs<ImportResult.Created>(pipeline.importFile(wav.path, source = "import"))
        // The file is live again, so the watched folder sees it as already imported.
        assertEquals(again.recordingId, assertIs<ImportResult.Duplicate>(pipeline.importFile(wav.path, source = "watched")).existingRecordingId)
    }

    @Test fun an_edited_watched_file_is_a_new_recording() = withPipeline { container, pipeline, wav ->
        val first = assertIs<ImportResult.Created>(pipeline.importFile(wav.path, source = "watched"))
        container.repository.deleteRecordingHard(first.recordingId)
        silentWav(wav, sample = 7)
        assertIs<ImportResult.Created>(pipeline.importFile(wav.path, source = "watched"))
    }

    @Test fun the_same_audio_in_another_container_stays_deleted() = withPipeline { container, pipeline, wav ->
        val first = assertIs<ImportResult.Created>(pipeline.importFile(wav.path, source = "watched"))
        container.repository.deleteRecordingHard(first.recordingId)
        // Same samples, different file bytes: an extra chunk before the audio.
        val retagged = silentWav(File(wav.parentFile, "retagged.wav"), extraChunk = true)
        assertNotEquals(sha256File(wav.path), sha256File(retagged.path))
        assertIs<ImportResult.Deleted>(pipeline.importFile(retagged.path, source = "watched"))
    }

    /** Real normalizing, counted, keeping the outputs. */
    private class CountingNormalize {
        val outputs = mutableListOf<String>()
        val call: suspend (String) -> NormalizedAudio = { path ->
            AudioNormalizer().normalizeToWav(path).also { outputs += it.wavPath }
        }
    }

    @Test fun a_retagged_copy_of_a_deleted_recording_is_only_decoded_once() = withPipeline { container, _, wav ->
        val normalize = CountingNormalize()
        val pipeline = AudioImportPipeline(container, normalize = normalize.call)
        val first = assertIs<ImportResult.Created>(pipeline.importFile(wav.path, source = "watched"))
        container.repository.deleteRecordingHard(first.recordingId)
        val retagged = silentWav(File(wav.parentFile, "retagged.wav"), extraChunk = true)
        assertIs<ImportResult.Deleted>(pipeline.importFile(retagged.path, source = "watched"))
        assertEquals(2, normalize.outputs.size)
        assertFalse(File(normalize.outputs[1]).exists(), "decoding the copy recreated audio the user deleted")

        assertIs<ImportResult.Deleted>(pipeline.importFile(retagged.path, source = "watched"))
        assertEquals(2, normalize.outputs.size, "every sync decodes the deleted copy again")
    }

    @Test fun a_retagged_copy_of_a_live_recording_is_a_duplicate_and_leaves_no_second_file() = withPipeline { container, _, wav ->
        val normalize = CountingNormalize()
        val pipeline = AudioImportPipeline(container, normalize = normalize.call)
        val first = assertIs<ImportResult.Created>(pipeline.importFile(wav.path))
        val retagged = silentWav(File(wav.parentFile, "retagged.wav"), extraChunk = true)

        val duplicate = assertIs<ImportResult.Duplicate>(pipeline.importFile(retagged.path))
        assertEquals(first.recordingId, duplicate.existingRecordingId)
        assertEquals(first.recordingId, container.repository.getMeta("import_sha256_${sha256File(retagged.path)}"))
        assertTrue(File(first.wavPath).exists(), "the original recording lost its audio")
        assertFalse(File(normalize.outputs[1]).exists(), "the duplicate's normalized copy was left behind")

        assertIs<ImportResult.Duplicate>(pipeline.importFile(retagged.path))
        assertEquals(2, normalize.outputs.size, "a known copy was decoded again")
    }

    @Test fun manual_source_match_warns_without_restoring() = trashMatch("import", contentMatch = false)
    @Test fun shared_source_match_warns_without_restoring() = trashMatch("share", contentMatch = false)
    @Test fun watched_source_match_warns_without_restoring() = trashMatch(WATCHED_SOURCE, contentMatch = false)
    @Test fun manual_content_match_warns_without_restoring() = trashMatch("import", contentMatch = true)
    @Test fun shared_content_match_warns_without_restoring() = trashMatch("share", contentMatch = true)
    @Test fun watched_content_match_warns_without_restoring() = trashMatch(WATCHED_SOURCE, contentMatch = true)

    private fun trashMatch(source: String, contentMatch: Boolean) {
        val enqueues = java.util.concurrent.atomic.AtomicInteger()
        withPipeline(onEnqueue = { enqueues.incrementAndGet() }) { container, _, wav ->
            val normalize = CountingNormalize()
            val pipeline = AudioImportPipeline(container, normalize = normalize.call)
            val first = assertIs<ImportResult.Created>(pipeline.importFile(wav.path))
            container.repository.trashRecording(first.recordingId)
            val trashed = container.repository.getRecording(first.recordingId)!!
            val offered = if (contentMatch) silentWav(File(wav.parentFile, "retagged.wav"), extraChunk = true) else wav
            val copyName = if (source == WATCHED_SOURCE) "watched/trash-match.wav" else "trash-match.wav"
            val copy = ownedCopy(offered, copyName)
            val sourceHash = sha256File(copy.path)
            if (contentMatch) assertNotEquals(sha256File(wav.path), sourceHash, "must reach the content-hash lookup")

            suspend fun importCopy(): ImportResult = if (source == "share") pipeline.importSharedDetached(copy.path)
                else pipeline.importFile(copy.path, source = source)
            val result = assertIs<ImportResult.InTrash>(importCopy())
            assertEquals(first.recordingId, result.existingRecordingId)
            assertEquals(if (contentMatch) first.sha256 else sourceHash, result.sha256)
            assertEquals(trashed, container.repository.getRecording(first.recordingId), "the trashed row changed")
            assertEquals(listOf(first.recordingId to first.wavPath), container.repository.getAllRecordingFileRefs(), "a second row was created")
            assertEquals(first.recordingId, container.repository.getMeta("import_sha256_$sourceHash"))
            assertEquals(first.recordingId, container.repository.getMeta("import_sha256_${first.sha256}"))
            assertFalse(copy.exists(), "the source scratch copy was left behind")
            assertTrue(File(first.wavPath).exists(), "the trashed recording lost its audio")
            assertEquals(if (contentMatch) 2 else 1, normalize.outputs.size)
            if (contentMatch) assertFalse(File(normalize.outputs.last()).exists(), "the unused normalized copy survived")

            // The content match remembers its source too; the next offer must not decode or create anything.
            ownedCopy(offered, copyName)
            assertIs<ImportResult.InTrash>(importCopy())
            assertEquals(if (contentMatch) 2 else 1, normalize.outputs.size)
            assertEquals(trashed, container.repository.getRecording(first.recordingId))
            assertFalse(copy.exists())
            assertEquals(1, enqueues.get(), "a trash match enqueued processing")
        }
    }

    @Test fun an_imported_recordings_normalized_file_goes_with_it() = withPipeline { container, _, wav ->
        val normalize = CountingNormalize()
        val pipeline = AudioImportPipeline(container, normalize = normalize.call)
        val first = assertIs<ImportResult.Created>(pipeline.importFile(ownedCopy(wav, "picked.wav").path))
        assertTrue(File(normalize.outputs[0]).exists())
        assertTrue(container.deleteRecordingForever(first.recordingId))
        assertFalse(File(normalize.outputs[0]).exists(), "the recording's audio stayed on disk")
        assertEquals(null, container.repository.getRecording(first.recordingId))
    }

    @Test fun a_picked_copy_is_removed_once_it_is_imported() = withPipeline { _, pipeline, wav ->
        val picked = ownedCopy(wav, "picked.wav")
        assertIs<ImportResult.Created>(pipeline.importFile(picked.path))
        assertFalse(picked.exists(), "the source copy stayed in the cache")
    }

    @Test fun a_file_outside_the_import_cache_is_left_alone() = withPipeline { _, pipeline, wav ->
        assertIs<ImportResult.Created>(pipeline.importFile(wav.path))
        assertTrue(wav.exists(), "a file the app does not own was deleted")
    }

    @Test fun a_watched_scratch_copy_goes_before_its_recording_is_deleted() = withPipeline { container, pipeline, wav ->
        val copy = ownedCopy(wav, "watched/copy.wav")
        val first = assertIs<ImportResult.Created>(pipeline.importFile(copy.path, source = "watched"))
        val audio = File(container.repository.getRecording(first.recordingId)!!.local_path!!)
        assertFalse(copy.exists(), "the watched scratch copy remained")
        assertTrue(audio.isFile, "the recording lost its normalized audio")
        assertTrue(container.deleteRecordingForever(first.recordingId))
        assertFalse(audio.exists(), "the recording's audio remained after delete")
    }

    @Test fun a_watched_copy_of_a_deleted_recording_is_removed_by_the_sync() = withPipeline { container, pipeline, wav ->
        val copy = ownedCopy(wav, "watched/copy.wav")
        val first = assertIs<ImportResult.Created>(pipeline.importFile(copy.path, source = "watched"))
        assertTrue(container.deleteRecordingForever(first.recordingId))
        val recopied = ownedCopy(wav, "watched/copy.wav")
        assertIs<ImportResult.Deleted>(pipeline.importFile(recopied.path, source = "watched"))
        assertFalse(recopied.exists(), "the sync's copy of deleted audio stayed on disk")
    }

    @Test fun audio_another_recording_uses_is_kept() = withPipeline { container, _, wav ->
        val shared = ownedCopy(wav, "normalized/shared.wav")
        for (id in listOf("rec-a", "rec-b")) container.addRow(id, shared.path)
        assertTrue(container.deleteRecordingForever("rec-a"))
        assertTrue(shared.exists(), "another recording lost its audio")
        assertEquals(null, container.repository.getRecording("rec-a"))
    }

    @Test fun audio_outside_app_storage_is_kept() = withPipeline { container, _, wav ->
        container.addRow("rec-a", wav.path)
        assertTrue(container.deleteRecordingForever("rec-a"))
        assertTrue(wav.exists(), "a file outside app storage was deleted")
    }

    @Test fun a_file_that_cannot_be_deleted_keeps_the_recording() = withPipeline { container, _, wav ->
        // A non-empty directory at the path can't be deleted as a file.
        val stuck = ownedCopy(wav, "normalized/stuck.wav/inner.wav").parentFile!!
        container.addRow("rec-a", stuck.path)
        assertFalse(container.deleteRecordingForever("rec-a"), "a failed delete was reported as done")
        assertNotNull(container.repository.getRecording("rec-a"), "the recording went but its audio stayed")
    }

    @Test fun importing_a_file_a_recording_uses_leaves_it_on_disk() = withPipeline { container, pipeline, wav ->
        val stored = File(recordingsDir(), "rec-x.wav").also { it.parentFile!!.mkdirs(); wav.copyTo(it, overwrite = true) }
        container.addRow("rec-x", stored.path)
        assertIs<ImportResult.Created>(pipeline.importFile(stored.path))
        assertTrue(stored.exists(), "importing a recording's own file deleted it")
    }

    @Test fun a_failed_import_of_a_file_a_recording_uses_leaves_it_on_disk() = withPipeline { container, _, wav ->
        val stored = File(recordingsDir(), "rec-x.bin").also { it.parentFile!!.mkdirs(); wav.copyTo(it, overwrite = true) }
        container.addRow("rec-x", stored.path)
        val failing = AudioImportPipeline(container, normalize = { error("synthetic decode failure") })
        assertIs<ImportResult.Failed>(failing.importFile(stored.path))
        assertTrue(stored.exists(), "a failed import deleted the only copy of a recording")
    }

    @Test fun a_normalized_file_is_not_a_source_copy() = withPipeline { _, _, wav ->
        val normalized = ownedCopy(wav, "normalized/rec.wav")
        assertFalse(isImportCacheCopy(normalized.path))
        assertTrue(isImportCacheCopy(ownedCopy(wav, "picked.wav").path))
        assertTrue(isImportCacheCopy(ownedCopy(wav, "watched/copy.wav").path))
    }

    @Test fun app_owned_path_fails_closed() = withPipeline { _, _, wav ->
        val recordings = recordingsDir().apply { mkdirs() }
        val imports = File(ownedCopy(wav, "picked.wav").parentFile!!.path)
        assertTrue(isAppOwnedAudioPath(File(recordings, "rec.wav").path))
        assertFalse(isAppOwnedAudioPath(recordings.path), "the root itself")
        assertFalse(isAppOwnedAudioPath(imports.path), "the root itself")
        assertFalse(isAppOwnedAudioPath(recordings.path + "-evil/rec.wav"), "a sibling with the same prefix")
        assertFalse(isAppOwnedAudioPath(File(recordings, "../outside.wav").path), "a path that climbs out")
        val outside = Files.createTempFile("velum-outside", ".wav")
        val link = File(recordings, "link.wav").toPath()
        Files.deleteIfExists(link)
        Files.createSymbolicLink(link, outside)
        assertFalse(isAppOwnedAudioPath(link.toString()), "a symlink out")
        assertFalse(isImportCacheCopy(link.toString()))
        Files.delete(link)
        Files.delete(outside)
        assertFalse(isImportCacheCopy(File(recordings, "rec.wav").path), "the recordings folder is not the import cache")
        assertFalse(isImportCacheCopy(imports.path), "the root itself")
    }

    @Test fun a_mic_style_recording_loses_its_file() = withPipeline { container, _, wav ->
        val mic = File(recordingsDir(), "rec-a.wav").also { it.parentFile!!.mkdirs(); wav.copyTo(it, overwrite = true) }
        container.addRow("rec-a", mic.path)
        assertTrue(container.deleteRecordingForever("rec-a"))
        assertFalse(mic.exists(), "the recording's audio stayed on disk")
    }

    @Test fun a_trashed_recording_keeps_a_file_it_shares() = withPipeline { container, _, wav ->
        val shared = ownedCopy(wav, "normalized/shared.wav")
        for (id in listOf("rec-a", "rec-trashed")) container.addRow(id, shared.path)
        container.repository.trashRecording("rec-trashed")
        assertTrue(container.deleteRecordingForever("rec-a"))
        assertTrue(shared.exists(), "a recording in the trash lost its audio")
    }

    @Test fun a_watched_duplicate_found_by_source_hash_is_tracked() = withPipeline { container, pipeline, wav ->
        val first = ownedCopy(wav, "watched/first.wav")
        val created = assertIs<ImportResult.Created>(pipeline.importFile(first.path, source = "watched"))
        val second = ownedCopy(wav, "watched/second.wav")
        assertIs<ImportResult.Duplicate>(pipeline.importFile(second.path, source = "watched"))
        assertTrue(container.deleteRecordingForever(created.recordingId))
        assertFalse(first.exists(), "the first watched copy stayed on disk")
        assertFalse(second.exists(), "the watched copy found by its source hash stayed on disk")
    }

    @Test fun a_failed_row_delete_is_reported_and_keeps_the_row() = withPipeline { container, _, wav ->
        val audio = ownedCopy(wav, "normalized/a.wav")
        container.addRow("rec-a", audio.path)
        container.addRow("rec-b", null)
        container.databaseHandle.driver.execute(
            null,
            "CREATE TRIGGER synthetic_failure BEFORE DELETE ON recording WHEN OLD.id = 'rec-a' BEGIN SELECT RAISE(ABORT, 'synthetic'); END",
            0,
        )
        assertFalse(container.deleteRecordingForever("rec-a"), "a failed row delete must be reported")
        assertNotNull(container.repository.getRecording("rec-a"))
        // The next item of an Empty trash still runs.
        assertTrue(container.deleteRecordingForever("rec-b"))
        assertEquals(null, container.repository.getRecording("rec-b"))
    }

    @Test fun leaving_the_screen_does_not_cut_a_delete_in_half() = withPipeline { container, _, wav ->
        val audio = ownedCopy(wav, "normalized/a.wav")
        container.addRow("rec-a", audio.path)
        coroutineScope {
            launch {
                cancel()
                runCatching { container.deleteRecordingForever("rec-a") }
            }.join()
        }
        assertFalse(audio.exists(), "the audio should be gone")
        assertEquals(null, container.repository.getRecording("rec-a"), "the file went but the row stayed")
    }

    @Test fun a_refused_watched_copy_is_removed() = withPipeline { container, pipeline, wav ->
        val copy = ownedCopy(wav, "watched/copy.wav")
        val result = container.wipeGate.wiping(1.seconds) { pipeline.importFile(copy.path, source = "watched") }
        assertEquals(ImportResult.Failed("wipe_in_progress"), result)
        assertFalse(copy.exists(), "a watched copy outlived the wipe")
    }

    @Test fun a_refused_import_removes_its_cache_copy() = withPipeline { container, _, wav ->
        val copy = ownedCopy(wav, "picked.wav")
        val pipeline = AudioImportPipeline(container, normalize = { throw WipeInProgressException() })
        assertEquals(ImportResult.Failed("wipe_in_progress"), pipeline.importFile(copy.path))
        assertFalse(copy.exists(), "the copy outlived the wipe")
    }

    private fun recordingsDir() = File(recordingsDirectoryPath())

    @Test fun the_screen_deletes_through_the_container() {
        val source = File("src/commonMain/kotlin/dev/velum/app/ui/files/FilesScreen.kt").readText()
        assertFalse("deleteRecordingHard" in source, "the screen removes rows without their files")
        assertTrue("deleteRecordingForever" in source)
    }

    @Test fun an_unused_normalized_copy_goes_before_its_tombstone() = withPipeline { container, _, wav ->
        val normalize = CountingNormalize()
        val pipeline = AudioImportPipeline(container, normalize = normalize.call)
        val first = assertIs<ImportResult.Created>(pipeline.importFile(wav.path, source = "watched"))
        container.repository.deleteRecordingHard(first.recordingId)
        val retagged = silentWav(File(wav.parentFile, "retagged.wav"), extraChunk = true)
        failMetaWrite(container, "import_deleted_${sha256File(retagged.path)}")
        assertIs<ImportResult.Failed>(pipeline.importFile(retagged.path, source = "watched"))
        assertFalse(File(normalize.outputs[1]).exists(), "a failed tombstone write left the deleted audio on disk")
    }

    @Test fun a_duplicates_normalized_copy_goes_before_its_key() = withPipeline { container, _, wav ->
        val normalize = CountingNormalize()
        val pipeline = AudioImportPipeline(container, normalize = normalize.call)
        assertIs<ImportResult.Created>(pipeline.importFile(wav.path))
        val retagged = silentWav(File(wav.parentFile, "retagged.wav"), extraChunk = true)
        failMetaWrite(container, "import_sha256_${sha256File(retagged.path)}")
        assertIs<ImportResult.Failed>(pipeline.importFile(retagged.path))
        assertFalse(File(normalize.outputs[1]).exists(), "a failed key write left the duplicate's copy on disk")
    }

    @Test fun a_refused_shared_import_removes_its_copy() = withPipeline { container, _, wav ->
        val copy = ownedCopy(wav, "shared.wav")
        val pipeline = AudioImportPipeline(container, normalize = { throw WipeInProgressException() })
        assertEquals(ImportResult.Failed("wipe_in_progress"), pipeline.importShared(copy.path))
        assertFalse(copy.exists(), "the shared copy outlived the wipe")
    }

    @Test fun only_direct_copies_count_as_import_copies() = withPipeline { _, _, wav ->
        val imports = ownedCopy(wav, "picked.wav").parentFile!!
        val outside = Files.createTempFile("velum-outside", ".wav")
        val link = File(imports, "link.wav").toPath()
        Files.createSymbolicLink(link, outside)
        assertFalse(isImportCacheCopy(link.toString()), "a link inside imports/ pointing outside")
        assertFalse(isAppOwnedAudioPath(link.toString()), "a link inside imports/ pointing outside")
        Files.delete(link)
        Files.delete(outside)
        assertFalse(isImportCacheCopy(File(imports, "watched/../../escaped.wav").path), "a path that climbs out")
        assertFalse(isImportCacheCopy(ownedCopy(wav, "watched/normalized/x.wav").path), "deeper than watched/")
        assertFalse(isImportCacheCopy(File(imports, "watched").path), "the directory itself")
    }

    @Test fun a_watched_copy_is_removed_after_a_cancelled_sync() = withPipeline { container, _, wav ->
        val copy = ownedCopy(wav, "watched/copy.wav")
        val normalizing = CompletableDeferred<Unit>()
        val neverFinishes = CompletableDeferred<NormalizedAudio>()
        val held = AudioImportPipeline(container, normalize = { normalizing.complete(Unit); neverFinishes.await() })
        coroutineScope {
            val job = launch(Dispatchers.Default) { held.importFile(copy.path, source = "watched") }
            normalizing.await()
            job.cancelAndJoin()
        }
        assertFalse(copy.exists(), "cancelled sync left its scratch copy")
        assertTrue(container.repository.getAllRecordings().isEmpty(), "cancelled import created a recording")
    }

    @Test fun a_watched_copy_is_removed_after_a_duplicate() = withPipeline { container, pipeline, wav ->
        val first = ownedCopy(wav, "watched/first.wav")
        val created = assertIs<ImportResult.Created>(pipeline.importFile(first.path, source = "watched"))
        val audio = File(container.repository.getRecording(created.recordingId)!!.local_path!!)
        val second = ownedCopy(wav, "watched/second.wav")
        val duplicate = assertIs<ImportResult.Duplicate>(pipeline.importFile(second.path, source = "watched"))
        assertEquals(created.recordingId, duplicate.existingRecordingId)
        assertFalse(second.exists(), "duplicate scratch copy remained")
        assertTrue(audio.isFile, "duplicate removed the winner's audio")
    }

    @Test fun a_delete_waits_for_a_running_save_and_takes_its_file() = withPipeline { container, _, _ ->
        val session = 1_700_000_000L
        val serial = "881FAKE1"
        val old = File(recordingsDir(), "rec-a.ogg").also { it.parentFile!!.mkdirs(); it.writeBytes(ByteArray(8)) }
        container.repository.insertRecording(
            id = "rec-a", sessionId = session, folderId = null, title = "Synthetic", source = "note_pro", deviceSerial = serial,
            startedAt = 1_700_000_000L, durationSeconds = 1, sizeBytes = 8, localPath = old.path,
            transcript = "kept", summaryMarkdown = "kept", status = "ready",
        )
        val saving = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val audio = DownloadedSessionAudio(
            sessionId = session, deviceSerial = serial, startedAtEpochSeconds = 1_700_000_000L,
            writeDecryptedAudio = { sink ->
                sink("RIFFsynthetic".encodeToByteArray())
                saving.countDown()
                finish.await()
                sink(ByteArray(64))
                77L
            },
        )
        coroutineScope {
            val save = async(Dispatchers.Default) { container.persistDownloadedSession(audio) }
            withContext(Dispatchers.IO) { saving.await() }
            val delete = async(Dispatchers.Default) { container.deleteRecordingForever("rec-a") }
            delay(300) // without the lock the delete is done by now, and the save below writes an orphan
            finish.countDown()
            save.await()
            assertTrue(delete.await())
        }
        assertEquals(null, container.repository.getRecording("rec-a"))
        assertEquals(emptyList(), recordingsDir().listFiles().orEmpty().filter { it.isFile }.map { it.name }, "the save's file outlived the delete")
    }


    private fun failMetaWrite(container: AppContainer, key: String) {
        container.databaseHandle.driver.execute(
            null,
            "CREATE TRIGGER synthetic_failure BEFORE INSERT ON app_meta WHEN NEW.key = '$key' BEGIN SELECT RAISE(ABORT, 'synthetic'); END",
            0,
        )
    }

    private suspend fun AppContainer.addRow(id: String, path: String?) = repository.insertRecording(
        id = id, sessionId = null, folderId = null, title = "Synthetic", source = "import", deviceSerial = null,
        startedAt = 1_700_000_000L, durationSeconds = 1, sizeBytes = 0, localPath = path,
        transcript = null, summaryMarkdown = null, status = "ready",
    )

    /** A copy of [wav] where the app keeps imports. */
    private fun ownedCopy(wav: File, name: String): File {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return File(context.cacheDir, "imports/$name").also { it.parentFile!!.mkdirs(); wav.copyTo(it, overwrite = true) }
    }

    private fun silentWav(file: File, sampleRate: Int = 16_000, durationMs: Int = 200, sample: Short = 0, extraChunk: Boolean = false): File {
        val samples = sampleRate * durationMs / 1000
        val dataSize = samples * 2
        val extra = if (extraChunk) 8 + 4 else 0
        val buffer = ByteBuffer.allocate(44 + extra + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + extra + dataSize).put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2)
            .putShort(2).putShort(16)
        if (extraChunk) buffer.put("LIST".toByteArray()).putInt(4).put("INFO".toByteArray())
        buffer.put("data".toByteArray()).putInt(dataSize)
        repeat(samples) { buffer.putShort(sample) }
        file.writeBytes(buffer.array())
        return file
    }
}
