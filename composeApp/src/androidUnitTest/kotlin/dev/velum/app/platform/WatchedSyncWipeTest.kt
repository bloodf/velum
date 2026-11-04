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
        context.deleteDatabase("velum.db")
        val root = ModelStoreDir(Files.createTempDirectory("velum-sync-wipe").toString())
        val container = AppContainer(
            InMemorySecureStore(), DriverFactory(context), modelStoreRoot = root,
            recordingJobSchedulerFactory = { _, _ -> RecordingJobScheduler { _, _, _, _ -> dev.velum.ai.pipeline.EnqueueResult.STARTED_OR_REPLACED } },
        )
        context.getSharedPreferences("velum_watched_folders", Context.MODE_PRIVATE).edit()
            .putString("tree_uris", FakeDocumentsProvider.treeUri().toString()).commit()
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
            syncWatchedFolders(pipeline) { _, _, _ -> scanned = true; emptyList() }
        }
        assertFalse(scanned, "the sync scanned and copied during the wipe")
        assertEquals(emptyList(), results)
    }

    @Test fun a_wipe_stops_a_running_scan_and_waits_for_it() = withPipeline { container, pipeline ->
        val scanning = CompletableDeferred<Unit>()
        coroutineScope {
            val sync = async(Dispatchers.Default) {
                syncWatchedFolders(pipeline) { _, _, checkActive ->
                    scanning.complete(Unit)
                    // Bounded, so a scan that ignores the stop fails this test instead of hanging it.
                    repeat(4_000) {
                        checkActive()
                        Thread.sleep(5)
                    }
                    emptyList()
                }
            }
            scanning.await()
            assertTrue(container.wipeGate.wiping(10.seconds) { it }, "the wipe did not wait for the scan")
            assertEquals(emptyList(), sync.await())
        }
    }

    @Test fun a_stopped_walk_copies_nothing() {
        Robolectric.setupContentProvider(FakeDocumentsProvider::class.java, FakeDocumentsProvider.AUTHORITY)
        installAppContext(context)
        val copies = File(context.cacheDir, "imports/watched").apply { deleteRecursively() }
        val checks = AtomicInteger()
        var stopped = false
        try {
            WatchedFolderStore.scanNewAudio(context, FakeDocumentsProvider.treeUri().toString()) {
                checks.incrementAndGet()
                throw CancellationException("synthetic")
            }
        } catch (_: CancellationException) {
            stopped = true
        }
        assertTrue(stopped, "the walk swallowed the stop")
        assertEquals(1, checks.get(), "the walk went on to the next file")
        assertEquals(emptyList(), copies.listFiles().orEmpty().toList(), "a file was copied after the stop")
    }
}
