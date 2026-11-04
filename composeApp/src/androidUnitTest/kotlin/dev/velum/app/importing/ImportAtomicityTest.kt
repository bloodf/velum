package dev.velum.app.importing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.velum.ai.local.ModelStoreDir
import dev.velum.ai.pipeline.RecordingJobScheduler
import dev.velum.app.di.AppContainer
import dev.velum.app.platform.NormalizedAudio
import dev.velum.core.data.DriverFactory
import dev.velum.core.security.InMemorySecureStore
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** #391: the dedupe check, the insert and the keys are one step, so two imports of one file make one recording. */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class, sdk = [34])
class ImportAtomicityTest {
    private fun withContainer(block: suspend (AppContainer, File) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("velum.db")
        val root = ModelStoreDir(Files.createTempDirectory("velum-atomic").toString())
        val container = AppContainer(
            InMemorySecureStore(), DriverFactory(context), modelStoreRoot = root,
            recordingJobSchedulerFactory = { _, _ -> RecordingJobScheduler { _, _, _, _ -> } },
        )
        val dir = File(context.cacheDir, "atomic-fixtures").apply { deleteRecursively(); mkdirs() }
        try {
            runBlocking { block(container, dir) }
        } finally {
            container.databaseHandle.driver.close()
            root.deleteRecursively()
            dir.deleteRecursively()
        }
    }

    @Test fun two_concurrent_imports_of_one_file_create_one_recording() = withContainer { container, dir ->
        val source = File(dir, "synthetic.wav").apply { writeBytes(ByteArray(64) { it.toByte() }) }
        // Both imports pass the early duplicate check, then meet here, so both reach the insert. Each gets its own
        // normalized file; the loser's must be removed and the winner's kept.
        val bothNormalizing = CountDownLatch(2)
        val calls = AtomicInteger()
        val pipeline = AudioImportPipeline(container, normalize = {
            val wav = File(dir, "normalized-${calls.incrementAndGet()}.wav").apply { writeBytes(ByteArray(64)) }
            bothNormalizing.countDown()
            check(bothNormalizing.await(10, TimeUnit.SECONDS)) { "the second import never arrived" }
            NormalizedAudio(wav.path, 16_000, 1_000, null, "synthetic-content-hash")
        })
        val results = coroutineScope { (1..2).map { async(Dispatchers.Default) { pipeline.importFile(source.path) } }.awaitAll() }
        val created = results.filterIsInstance<ImportResult.Created>()
        val duplicates = results.filterIsInstance<ImportResult.Duplicate>()
        assertEquals(1, created.size, "results: $results")
        assertEquals(1, duplicates.size, "results: $results")
        assertEquals(created.single().recordingId, duplicates.single().existingRecordingId)
        assertEquals(1, container.repository.storageUsage().recordingCount)
        val survivors = dir.listFiles { f -> f.name.startsWith("normalized-") }.orEmpty().map { it.path }
        assertEquals(listOf(container.repository.getRecording(created.single().recordingId)!!.local_path), survivors)
    }

    @Test fun a_watched_import_is_refused_when_its_file_is_deleted_while_it_normalizes() = withContainer { container, dir ->
        val source = File(dir, "synthetic.wav").apply { writeBytes(ByteArray(64) { it.toByte() }) }
        val wav = File(dir, "normalized.wav").apply { writeBytes(ByteArray(64)) }
        val sourceHash = dev.velum.app.platform.sha256File(source.path)
        val pipeline = AudioImportPipeline(container, normalize = {
            // The tombstone checks that run before the insert have passed for the source (the content one is read
            // after this returns, so only the source tombstone can land in the window); the user deletes it now.
            container.repository.setMeta("import_deleted_$sourceHash", "1")
            NormalizedAudio(wav.path, 16_000, 1_000, null, "synthetic-content-hash")
        })
        val result = pipeline.importFile(source.path, source = WATCHED_SOURCE)
        assertEquals(ImportResult.Deleted("synthetic-content-hash"), result)
        assertEquals(0, container.repository.storageUsage().recordingCount, "the deleted recording came back")
        assertNotNull(container.repository.getMeta("import_deleted_$sourceHash"), "later syncs would decode this copy again")
        assertFalse(wav.exists(), "the refused copy was left behind")
    }
}
