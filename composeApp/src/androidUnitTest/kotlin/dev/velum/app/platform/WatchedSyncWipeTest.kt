package dev.velum.app.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.velum.ai.local.ModelStoreDir
import dev.velum.ai.pipeline.RecordingJobScheduler
import dev.velum.app.di.AppContainer
import dev.velum.app.importing.AudioImportPipeline
import dev.velum.core.data.DriverFactory
import dev.velum.core.security.InMemorySecureStore
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/** #328: a watched-folder sync is stopped by a wipe, including its scan and copying, not only its imports. */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class, sdk = [34])
class WatchedSyncWipeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun withPipeline(block: suspend (AppContainer, AudioImportPipeline) -> Unit) {
        installAppContext(context)
        installRecordingFilesDir(context.filesDir.path)
        context.deleteDatabase("velum.db")
        val root = ModelStoreDir(Files.createTempDirectory("velum-sync-wipe").toString())
        val container = AppContainer(
            InMemorySecureStore(), DriverFactory(context), modelStoreRoot = root,
            recordingJobSchedulerFactory = { _, _ -> RecordingJobScheduler { _, _, _, _ -> } },
        )
        context.getSharedPreferences("velum_watched_folders", Context.MODE_PRIVATE).edit()
            .clear().putString("tree_uris", FakeDocumentsProvider.treeUri().toString()).commit()
        try {
            runBlocking { block(container, AudioImportPipeline(container)) }
        } finally {
            context.getSharedPreferences("velum_watched_folders", Context.MODE_PRIVATE).edit().clear().commit()
            container.databaseHandle.driver.close()
            root.deleteRecursively()
        }
    }

    @Test fun a_sync_does_not_start_during_a_wipe() = withPipeline { container, pipeline ->
        var scanned = false
        val results = container.wipeGate.wiping(1.seconds) {
            syncWatchedFolders(pipeline) { _, _, _, _ -> scanned = true }
        }
        assertFalse(scanned, "the sync scanned and copied during the wipe")
        assertEquals(emptyList(), results)
    }

    @Test fun a_wipe_stops_a_running_scan_and_waits_for_it() = withPipeline { container, pipeline ->
        val scanning = CompletableDeferred<Unit>()
        coroutineScope {
            val sync = async(Dispatchers.Default) {
                syncWatchedFolders(pipeline) { _, _, checkActive, _ ->
                    scanning.complete(Unit)
                    // Bounded, so a scan that ignores the stop fails this test instead of hanging it.
                    repeat(4_000) {
                        checkActive()
                        Thread.sleep(5)
                    }
                }
            }
            scanning.await()
            assertTrue(container.wipeGate.wiping(10.seconds) { it }, "the wipe did not wait for the scan")
            assertEquals(emptyList(), sync.await())
        }
    }

    private suspend fun withSyntheticProvider(block: suspend () -> Unit) {
        Robolectric.setupContentProvider(FakeDocumentsProvider::class.java, FakeDocumentsProvider.AUTHORITY)
        val source = File(context.cacheDir, "scanner-provider.wav").apply { writeBytes(ByteArray(64)) }
        FakeDocumentsProvider.audioFile = source
        try {
            block()
        } finally {
            FakeDocumentsProvider.audioFile = null
            source.delete()
        }
    }

    @Test fun cancelled_consumer_removes_current_copy_and_does_not_stage_next() = withPipeline { _, _ ->
        withSyntheticProvider {
            var copied: File? = null
            var visits = 0
            try {
                WatchedFolderStore.scanNewAudio(context, FakeDocumentsProvider.treeUri().toString(), {}) { entry ->
                    copied = File(entry.absoluteCopyPath)
                    assertTrue(copied!!.exists())
                    visits++
                    throw CancellationException("synthetic")
                }
            } catch (_: CancellationException) { }
            assertEquals(1, visits)
            assertFalse(copied!!.exists())
            assertTrue(File(context.cacheDir, "imports/watched").listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun each_copy_is_removed_before_the_next_source_is_consumed() = withPipeline { _, _ ->
        withSyntheticProvider {
            var previous: File? = null
            var visits = 0
            WatchedFolderStore.scanNewAudio(context, FakeDocumentsProvider.treeUri().toString(), {}) { entry ->
                assertFalse(previous?.exists() ?: false)
                val current = File(entry.absoluteCopyPath)
                assertEquals(listOf(current), current.parentFile!!.listFiles().orEmpty().toList())
                assertTrue(current.exists())
                previous = current
                visits++
        }
        assertEquals(2, visits)
        assertFalse(previous!!.exists())
        }
    }


    @Test fun watched_multibyte_name_scans_and_imports() = withPipeline { container, pipeline ->
        Robolectric.setupContentProvider(FakeDocumentsProvider::class.java, FakeDocumentsProvider.AUTHORITY)
        val name = "音".repeat(70)
        val wav = File(context.cacheDir, "provider.wav")
        val bytes = java.nio.ByteBuffer.allocate(64).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(56).put("WAVEfmt ".toByteArray()).putInt(16)
        bytes.putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
        bytes.put("data".toByteArray()).putInt(20).put(ByteArray(20))
        wav.writeBytes(bytes.array())
        FakeDocumentsProvider.displayName = "$name.wav"
        FakeDocumentsProvider.audioFile = wav
        try {
            val results = syncWatchedFolders(pipeline) { context, uri, check, consume ->
                WatchedFolderStore.scanNewAudio(context, uri, check) { entry ->
                    assertTrue((File(entry.absoluteCopyPath).name + ".tmp").toByteArray(Charsets.UTF_8).size <= 255)
                    consume(entry)
                }
            }
            assertTrue(results.any { it is dev.velum.app.importing.ImportResult.Created }, "$results")
            assertEquals(name, container.repository.getAllRecordings().single().title)
        } finally {
            FakeDocumentsProvider.displayName = null
            FakeDocumentsProvider.audioFile = null
            wav.delete()
        }
    }

    @Test fun watched_content_identity_detects_replacement_and_retries_failures() = withPipeline { container, pipeline ->
        Robolectric.setupContentProvider(FakeDocumentsProvider::class.java, FakeDocumentsProvider.AUTHORITY)
        val wav = File(context.cacheDir, "identity-provider.wav")
        fun audio(sample: Byte): ByteArray {
            val bytes = java.nio.ByteBuffer.allocate(64).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            bytes.put("RIFF".toByteArray()).putInt(56).put("WAVEfmt ".toByteArray()).putInt(16)
            bytes.putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
            bytes.put("data".toByteArray()).putInt(20).put(ByteArray(20) { sample })
            return bytes.array()
        }
        FakeDocumentsProvider.audioFile = wav
        try {
            // Both provider entries have fixed URIs, timestamps and sizes across every scan.
            wav.writeBytes(audio(0))
            val first = syncWatchedFolders(pipeline)
            assertEquals(1, first.count { it is dev.velum.app.importing.ImportResult.Created })
            assertEquals(1, container.repository.getAllRecordings().size)

            wav.writeBytes(audio(1))
            val changed = syncWatchedFolders(pipeline)
            assertEquals(1, changed.count { it is dev.velum.app.importing.ImportResult.Created }, "$changed")
            assertEquals(2, container.repository.getAllRecordings().size)

            val unchanged = syncWatchedFolders(pipeline)
            assertTrue(unchanged.all { it is dev.velum.app.importing.ImportResult.Duplicate }, "$unchanged")
            assertEquals(2, unchanged.size)
            assertEquals(2, container.repository.getAllRecordings().size)

            wav.writeBytes(ByteArray(64))
            val failed = syncWatchedFolders(pipeline)
            assertEquals(2, failed.count { it is dev.velum.app.importing.ImportResult.Failed }, "$failed")
            val retried = syncWatchedFolders(pipeline)
            assertEquals(2, retried.count { it is dev.velum.app.importing.ImportResult.Failed }, "$retried")
            wav.writeBytes(audio(2))
            val recovered = syncWatchedFolders(pipeline)
            assertEquals(1, recovered.count { it is dev.velum.app.importing.ImportResult.Created }, "$recovered")
            assertEquals(3, container.repository.getAllRecordings().size)
        } finally {
            FakeDocumentsProvider.audioFile = null
            wav.delete()
        }
    }

    @Test fun a_stopped_walk_copies_nothing() {
        Robolectric.setupContentProvider(FakeDocumentsProvider::class.java, FakeDocumentsProvider.AUTHORITY)
        installAppContext(context)
        val copies = File(context.cacheDir, "imports/watched").apply { deleteRecursively() }
        val checks = AtomicInteger()
        var stopped = false
        try {
            runBlocking {
                WatchedFolderStore.scanNewAudio(context, FakeDocumentsProvider.treeUri().toString(), {
                    checks.incrementAndGet()
                    throw CancellationException("synthetic")
                }) { error("stopped walk consumed a copy") }
            }
        } catch (_: CancellationException) {
            stopped = true
        }
        assertTrue(stopped, "the walk swallowed the stop")
        assertEquals(1, checks.get(), "the walk went on to the next file")
        assertEquals(emptyList(), copies.listFiles().orEmpty().toList(), "a file was copied after the stop")
    }
}
