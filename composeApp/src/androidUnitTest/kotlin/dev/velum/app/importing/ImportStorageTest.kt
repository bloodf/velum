package dev.velum.app.importing

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.velum.ai.local.ModelStoreDir
import dev.velum.ai.pipeline.RecordingJobScheduler
import dev.velum.app.di.AppContainer
import dev.velum.app.platform.installAppContext
import dev.velum.app.platform.installRecordingFilesDir
import dev.velum.app.platform.isImportCacheCopy
import dev.velum.app.platform.migrateCachedImports
import dev.velum.app.platform.recordingsDirectoryPath
import dev.velum.app.platform.NormalizedAudio
import dev.velum.app.platform.deleteImportCopies
import kotlinx.coroutines.launch
import dev.velum.core.data.DriverFactory
import dev.velum.core.security.InMemorySecureStore
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #318: imported audio lives with the other recordings, never in the evictable cache; the cache copy is gone. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ImportStorageTest {
    private lateinit var app: Application
    private lateinit var cache: File
    private lateinit var files: File
    private lateinit var container: AppContainer

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.deleteDatabase("velum.db")
        installAppContext(app)
        cache = app.cacheDir.also { File(it, "imports").deleteRecursively(); File(it, "share").deleteRecursively() }
        files = Files.createTempDirectory("velum-import-files").toFile()
        installRecordingFilesDir(files.path)
        container = AppContainer(
            InMemorySecureStore(), DriverFactory(app), modelStoreRoot = ModelStoreDir(File(files, "models").path),
            recordingJobSchedulerFactory = { _, _ -> RecordingJobScheduler { _, _, _, _ -> } },
        )
    }

    @After fun tearDown() {
        container.databaseHandle.driver.close(); files.deleteRecursively()
    }

    private fun wav(file: File, seed: Int = 0): File {
        file.parentFile.mkdirs()
        val samples = 1600
        val b = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt(36 + samples * 2); b.put("WAVE".toByteArray()); b.put("fmt ".toByteArray())
        b.putInt(16); b.putShort(1); b.putShort(1); b.putInt(16_000); b.putInt(32_000); b.putShort(2); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(samples * 2)
        repeat(samples) { b.putShort(((it * 7 + seed) % 1000).toShort()) }
        file.writeBytes(b.array()); return file
    }

    private fun inRecordings(path: String) = File(path).canonicalPath.startsWith(File(recordingsDirectoryPath()).canonicalPath + File.separator)

    // Picker, share sheet and watched folder all hand the pipeline a scratch copy under cacheDir.
    @Test fun every_entry_point_stores_audio_under_recordings_and_deletes_its_cache_copy() = runBlocking {
        val sources = mapOf(
            "import" to File(cache, "imports/100_picked.wav"),
            "share" to File(cache, "imports/share_200_shared.wav"),
            "watched" to File(cache, "imports/watched/300_folder.wav"),
        )
        var seed = 0
        for ((source, file) in sources) {
            wav(file, seed++)
            assertTrue("$source not treated as scratch", isImportCacheCopy(file.path))
            val result = AudioImportPipeline(container).importFile(file.path, source = source)
            assertTrue("$source: $result", result is ImportResult.Created)
            val row = container.repository.getRecording((result as ImportResult.Created).recordingId)!!
            assertTrue("$source row points at ${row.local_path}", inRecordings(row.local_path!!))
            assertTrue(File(row.local_path!!).isFile)
            // A watched folder's copy deliberately stays until its recording is deleted (it saves re-copying the file on every sync).
            if (source == "watched") assertTrue(file.exists()) else assertFalse("$source cache copy left behind", file.exists())
        }
        assertTrue(File(cache, "imports").walkTopDown().filter { it.isFile }.all { it.path.contains("/watched/") })
    }

    // Same audio, different source bytes: the content hash matches, so it reaches discardNormalized (not the source-hash exit).
    @Test fun a_content_duplicate_discards_its_new_wav_and_keeps_the_original_rows_file() = runBlocking {
        val first = wav(File(cache, "imports/1_a.wav"), 1)
        val created = AudioImportPipeline(container).importFile(first.path) as ImportResult.Created
        val originalWav = container.repository.getRecording(created.recordingId)!!.local_path!!
        val second = File(cache, "imports/2_b.bin").also { it.writeBytes(ByteArray(32) { 5 }) } // different source hash
        val dupWav = File(recordingsDirectoryPath().also { File(it).mkdirs() }, "import_dup.wav").also { it.writeBytes(ByteArray(44)) }
        val pipeline = AudioImportPipeline(container, normalize = {
            NormalizedAudio(dupWav.path, 16_000, 100, "audio/wav", created.sha256)
        })
        assertTrue(pipeline.importFile(second.path) is ImportResult.Duplicate)
        assertFalse("duplicate's new wav kept", dupWav.exists())
        assertTrue("the original recording's file was deleted", File(originalWav).isFile)
        assertFalse(second.exists())
    }

    // HIGH: a throw after the row exists must not delete the only copy the row now points to.
    @Test fun a_failure_after_the_row_exists_keeps_the_wav_the_row_points_to() = runBlocking {
        val failing = AppContainer(
            InMemorySecureStore(), DriverFactory(app), modelStoreRoot = ModelStoreDir(File(files, "models2").path),
            recordingJobSchedulerFactory = { _, _ -> RecordingJobScheduler { _, _, _, _ -> error("scheduler down") } },
        )
        try {
            val src = wav(File(cache, "imports/9_shared.wav"), 9)
            val result = AudioImportPipeline(failing).importFile(src.path, source = "share")
            assertTrue(result is ImportResult.Failed)
            val row = failing.repository.getAllRecordings().single()
            assertTrue("row points at a deleted file: ${row.local_path}", File(row.local_path!!).isFile)
        } finally {
            failing.databaseHandle.driver.close()
        }
    }

    @Test fun a_cancelled_import_before_the_row_removes_its_wav_but_keeps_the_incoming_copy() = runBlocking {
        val src = wav(File(cache, "imports/8_cancel.wav"), 8)
        val half = File(recordingsDirectoryPath().also { File(it).mkdirs() }, "import_half.wav").also { it.writeBytes(ByteArray(44)) }
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val pipeline = AudioImportPipeline(container, normalize = {
            NormalizedAudio(half.path, 16_000, 100, "audio/wav", "cancel-hash").also { started.complete(Unit) }
        })
        // Cancel right after normalize returned: the content-hash lookup is the next step.
        val job = kotlinx.coroutines.GlobalScope.launch { pipeline.importFile(src.path) }
        started.await(); job.cancel(); job.join()
        val rows = container.repository.getAllRecordings()
        if (rows.isEmpty()) assertFalse("orphan wav left", half.exists()) else assertTrue(File(rows.single().local_path!!).isFile)
    }

    @Test fun a_failed_import_still_removes_the_cache_copy() = runBlocking {
        val bad = File(cache, "imports/3_bad.wav").also { it.parentFile.mkdirs(); it.writeBytes(ByteArray(64) { 7 }) }
        assertTrue(AudioImportPipeline(container).importFile(bad.path) is ImportResult.Failed)
        assertFalse(bad.exists())
    }

    @Test fun a_file_the_user_chose_is_never_deleted() = runBlocking {
        val mine = wav(File(files, "user-music/mine.wav"), 9)
        assertFalse(isImportCacheCopy(mine.path))
        AudioImportPipeline(container).importFile(mine.path)
        assertTrue(mine.exists())
    }

    @Test fun the_wipe_removes_every_cache_copy_and_leaves_no_audio_outside_the_recordings_dir() = runBlocking {
        wav(File(cache, "imports/x.wav")); wav(File(cache, "imports/share_1_z.wav")); wav(File(cache, "imports/watched/y.wav"))
        val id = (AudioImportPipeline(container).importFile(wav(File(cache, "imports/w.wav"), 3).path) as ImportResult.Created).recordingId
        assertTrue(File(container.repository.getRecording(id)!!.local_path!!).isFile)
        deleteImportCopies()
        assertFalse(File(cache, "imports").exists())
    }

    @Test fun existing_rows_with_audio_in_the_cache_are_moved_and_repointed_once() = runBlocking {
        val cached = wav(File(cache, "imports/normalized/old_16000.wav"), 5)
        val raw = wav(File(cache, "imports/old.mp3"), 6)
        val id = container.createLocalRecording(title = "Old", source = "import", localPath = cached.path, status = "ready")
        container.repository.updateLocalPath(id, cached.path, 12, 3200)
        val evicted = container.createLocalRecording(title = "Gone", source = "import", localPath = File(cache, "imports/normalized/gone.wav").path, status = "ready")
        val bytes = cached.readBytes()

        assertEquals(1, migrateCachedImports(container.repository, File(recordingsDirectoryPath()), cache, nowMs = System.currentTimeMillis() + 2 * 24L * 3600 * 1000))
        val row = container.repository.getRecording(id)!!
        assertTrue(inRecordings(row.local_path!!))
        assertEquals(12L, row.duration_seconds); assertEquals(3200L, row.size_bytes)
        assertTrue(bytes.contentEquals(File(row.local_path!!).readBytes()))
        assertFalse(cached.exists()); assertFalse("unreferenced raw copy kept", raw.exists())
        assertTrue("evicted row left alone", container.repository.getRecording(evicted)!!.local_path!!.contains("/imports/"))

        assertEquals(0, migrateCachedImports(container.repository, File(recordingsDirectoryPath()), cache))
        assertTrue(File(container.repository.getRecording(id)!!.local_path!!).isFile)
    }

    // A share or pick may be waiting to be imported at the moment the migration runs at startup.
    @Test fun the_migration_keeps_a_recent_unreferenced_incoming_copy() = runBlocking {
        val pending = wav(File(cache, "imports/share_999_pending.wav"), 8)
        migrateCachedImports(container.repository, File(recordingsDirectoryPath()), cache)
        assertTrue(pending.exists())
    }

    // A crash between the normalized WAV and its row leaves an unreferenced import_ file the recordings sweep ignores.
    @Test fun a_day_old_unreferenced_import_output_is_swept_but_a_referenced_or_recent_one_is_kept() = runBlocking {
        val dir = File(recordingsDirectoryPath()).also { it.mkdirs() }
        val kept = wav(File(dir, "import_kept_16000_aaaaaaaaaaaa.wav"), 1)
        val id = container.createLocalRecording(title = "T", source = "import", localPath = kept.path, status = "ready")
        val orphan = wav(File(dir, "import_orphan_16000_bbbbbbbbbbbb.wav"), 2)
        val recent = wav(File(dir, "import_recent_16000_cccccccccccc.wav"), 3)
        val dayAgo = System.currentTimeMillis() - 2 * 24L * 3600 * 1000
        kept.setLastModified(dayAgo); orphan.setLastModified(dayAgo)
        migrateCachedImports(container.repository, dir, cache)
        assertTrue(kept.exists()); assertFalse(orphan.exists()); assertTrue(recent.exists())
        assertEquals(kept.path, container.repository.getRecording(id)!!.local_path)
    }

    // If the move fails partway, the row and its cache file are exactly as before: nothing is lost.
    @Test fun a_failed_move_leaves_the_row_and_the_cache_file_untouched() = runBlocking {
        val cached = wav(File(cache, "imports/normalized/stuck_16000.wav"), 4)
        val id = container.createLocalRecording(title = "S", source = "import", localPath = cached.path, status = "ready")
        val dir = File(recordingsDirectoryPath()).also { it.mkdirs() }
        File(dir, "$id.wav").mkdirs() // a directory where the file must go: the move cannot succeed
        assertEquals(0, migrateCachedImports(container.repository, dir, cache, nowMs = System.currentTimeMillis() + 2 * 24L * 3600 * 1000))
        assertEquals(cached.path, container.repository.getRecording(id)!!.local_path)
        assertTrue(cached.isFile)
    }

    // HIGH 3: a destination left by an earlier run is reused only if it is the very same audio.
    @Test fun a_same_size_destination_with_different_bytes_is_not_trusted() = runBlocking {
        val cached = wav(File(cache, "imports/normalized/same_16000.wav"), 4)
        val id = container.createLocalRecording(title = "S", source = "import", localPath = cached.path, status = "ready")
        val dir = File(recordingsDirectoryPath()).also { it.mkdirs() }
        File(dir, "$id.wav").writeBytes(ByteArray(cached.length().toInt()) { 1 }) // same size, other audio
        assertEquals(0, migrateCachedImports(container.repository, dir, cache))
        assertEquals(cached.path, container.repository.getRecording(id)!!.local_path)
        assertTrue(cached.isFile)
    }

    @Test fun a_different_size_destination_is_not_trusted() = runBlocking {
        val cached = wav(File(cache, "imports/normalized/size_16000.wav"), 4)
        val id = container.createLocalRecording(title = "S", source = "import", localPath = cached.path, status = "ready")
        val dir = File(recordingsDirectoryPath()).also { it.mkdirs() }
        File(dir, "$id.wav").writeBytes(ByteArray(10))
        assertEquals(0, migrateCachedImports(container.repository, dir, cache))
        assertEquals(cached.path, container.repository.getRecording(id)!!.local_path)
    }

    // HIGH 4: the copy finished, the process died before the repoint, then the cache was evicted.
    @Test fun a_complete_destination_with_the_source_gone_is_adopted_not_orphaned() = runBlocking {
        val evicted = File(cache, "imports/normalized/gone_16000.wav")
        val id = container.createLocalRecording(title = "G", source = "import", localPath = evicted.path, status = "ready")
        val dir = File(recordingsDirectoryPath()).also { it.mkdirs() }
        val rescued = wav(File(dir, "$id.wav"), 6)
        assertEquals(1, migrateCachedImports(container.repository, dir, cache, nowMs = System.currentTimeMillis() + 3 * 24L * 3600 * 1000))
        assertEquals(rescued.path, container.repository.getRecording(id)!!.local_path)
        assertTrue(rescued.isFile)
    }

    // The orphan sweep reads mtime; a moved file must look fresh even if the cache file was old.
    @Test fun a_moved_file_is_stamped_fresh_so_the_orphan_sweep_cannot_take_it() = runBlocking {
        val cached = wav(File(cache, "imports/normalized/old_16000.wav"), 4).also { it.setLastModified(1_000L) }
        val id = container.createLocalRecording(title = "O", source = "import", localPath = cached.path, status = "ready")
        val before = System.currentTimeMillis()
        migrateCachedImports(container.repository, File(recordingsDirectoryPath()), cache)
        assertTrue(File(container.repository.getRecording(id)!!.local_path!!).lastModified() >= before - 2_000)
    }

    // MEDIUM 5: two rows on one cache file each get their own file; the source goes once.
    @Test fun rows_sharing_one_cache_file_each_get_a_copy() = runBlocking {
        val cached = wav(File(cache, "imports/normalized/shared_16000.wav"), 4)
        val a = container.createLocalRecording(title = "A", source = "import", localPath = cached.path, status = "ready")
        val b = container.createLocalRecording(title = "B", source = "import", localPath = cached.path, status = "ready")
        assertEquals(2, migrateCachedImports(container.repository, File(recordingsDirectoryPath()), cache))
        val pa = container.repository.getRecording(a)!!.local_path!!; val pb = container.repository.getRecording(b)!!.local_path!!
        assertTrue(File(pa).isFile && File(pb).isFile)
        assertTrue(pa != pb)
        assertFalse(cached.exists())
    }

    @Test fun shared_cache_source_survives_a_partial_migration() = runBlocking {
        val cached = wav(File(cache, "imports/normalized/partial_16000.wav"), 4)
        val a = container.createLocalRecording(title = "A", source = "import", localPath = cached.path, status = "ready")
        val b = container.createLocalRecording(title = "B", source = "import", localPath = cached.path, status = "ready")
        val dir = File(recordingsDirectoryPath()).also { it.mkdirs() }
        File(dir, "$b.wav").mkdirs()
        migrateCachedImports(container.repository, dir, cache)
        assertTrue("Unmigrated row lost its source", cached.isFile || container.repository.getRecording(b)!!.local_path != cached.path)
        assertTrue(File(container.repository.getRecording(a)!!.local_path!!).isFile)
    }

    // Pending incoming copy and our own .tmp.
    @Test fun a_pending_incoming_copy_survives_the_sweep_and_an_old_tmp_goes() = runBlocking {
        val dir = File(recordingsDirectoryPath()).also { it.mkdirs() }
        val pending = wav(File(cache, "imports/share_1_pending.wav"), 1).also { it.setLastModified(1_000L) }
        val other = wav(File(cache, "imports/share_2_other.wav"), 2).also { it.setLastModified(1_000L) }
        val tmp = File(dir, "rec-x.wav.tmp").also { it.writeBytes(ByteArray(4)); it.setLastModified(1_000L) }
        migrateCachedImports(container.repository, dir, cache, pendingPaths = setOf(pending.path))
        assertTrue(pending.exists()); assertFalse(other.exists()); assertFalse(tmp.exists())
    }

    // MEDIUM 8: the wipe deletes what an interrupted import leaves in the recordings directory.
    @Test fun the_wipe_removes_import_residue_from_the_recordings_directory() = runBlocking {
        val dir = File(recordingsDirectoryPath()).also { it.mkdirs() }
        val junk = listOf("import_half_16000_abc.wav", "rec-x.wav.tmp", "import_x.wav.77.part").map { File(dir, it).also { f -> f.writeBytes(ByteArray(8)) } }
        container.wipeAllData()
        assertTrue("residue left: ${junk.filter { it.exists() }}", junk.none { it.exists() })
    }
}
