package dev.velum.app.importing

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.velum.ai.local.ModelStoreDir
import dev.velum.ai.pipeline.RecordingJobScheduler
import dev.velum.app.di.AppContainer
import dev.velum.app.platform.installAppContext
import dev.velum.app.platform.installRecordingFilesDir
import dev.velum.app.platform.isImportScratchPath
import dev.velum.app.platform.migrateCachedImports
import dev.velum.app.platform.recordingsDirectoryPath
import dev.velum.app.platform.wipeImportScratch
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
            assertTrue("$source not treated as scratch", isImportScratchPath(file.path))
            val result = AudioImportPipeline(container).importFile(file.path, source = source)
            assertTrue("$source: $result", result is ImportResult.Created)
            val row = container.repository.getRecording((result as ImportResult.Created).recordingId)!!
            assertTrue("$source row points at ${row.local_path}", inRecordings(row.local_path!!))
            assertTrue(File(row.local_path!!).isFile)
            assertFalse("$source cache copy left behind", file.exists())
        }
        assertTrue(File(cache, "imports").walkTopDown().none { it.isFile })
    }

    @Test fun a_duplicate_import_leaves_no_second_wav_and_still_deletes_the_cache_copy() = runBlocking {
        val first = wav(File(cache, "imports/1_a.wav"), 1)
        assertTrue(AudioImportPipeline(container).importFile(first.path) is ImportResult.Created)
        val before = File(recordingsDirectoryPath()).listFiles().orEmpty().map { it.name }.sorted()
        val again = wav(File(cache, "imports/2_a.wav"), 1)
        assertTrue(AudioImportPipeline(container).importFile(again.path) is ImportResult.Duplicate)
        assertEquals(before, File(recordingsDirectoryPath()).listFiles().orEmpty().map { it.name }.sorted())
        assertFalse(again.exists())
    }

    @Test fun a_failed_import_still_removes_the_cache_copy() = runBlocking {
        val bad = File(cache, "imports/3_bad.wav").also { it.parentFile.mkdirs(); it.writeBytes(ByteArray(64) { 7 }) }
        assertTrue(AudioImportPipeline(container).importFile(bad.path) is ImportResult.Failed)
        assertFalse(bad.exists())
    }

    @Test fun a_file_the_user_chose_is_never_deleted() = runBlocking {
        val mine = wav(File(files, "user-music/mine.wav"), 9)
        assertFalse(isImportScratchPath(mine.path))
        AudioImportPipeline(container).importFile(mine.path)
        assertTrue(mine.exists())
    }

    @Test fun the_wipe_removes_imports_and_share_residue() {
        wav(File(cache, "imports/x.wav")); wav(File(cache, "imports/watched/y.wav")); wav(File(cache, "share/z.wav"))
        wipeImportScratch()
        assertFalse(File(cache, "imports").exists())
        assertFalse(File(cache, "share").exists())
    }

    @Test fun existing_rows_with_audio_in_the_cache_are_moved_and_repointed_once() = runBlocking {
        val cached = wav(File(cache, "imports/normalized/old_16000.wav"), 5)
        val raw = wav(File(cache, "imports/old.mp3"), 6)
        val id = container.createLocalRecording(title = "Old", source = "import", localPath = cached.path, status = "ready")
        container.repository.updateLocalPath(id, cached.path, 12, 3200)
        val evicted = container.createLocalRecording(title = "Gone", source = "import", localPath = File(cache, "imports/normalized/gone.wav").path, status = "ready")
        val bytes = cached.readBytes()

        assertEquals(1, migrateCachedImports(container.repository, File(recordingsDirectoryPath()), cache))
        val row = container.repository.getRecording(id)!!
        assertTrue(inRecordings(row.local_path!!))
        assertEquals(12L, row.duration_seconds); assertEquals(3200L, row.size_bytes)
        assertTrue(bytes.contentEquals(File(row.local_path!!).readBytes()))
        assertFalse(cached.exists()); assertFalse("unreferenced raw copy kept", raw.exists())
        assertTrue("evicted row left alone", container.repository.getRecording(evicted)!!.local_path!!.contains("/imports/"))

        assertEquals(0, migrateCachedImports(container.repository, File(recordingsDirectoryPath()), cache))
        assertTrue(File(container.repository.getRecording(id)!!.local_path!!).isFile)
    }
}
