package dev.velum.app.importing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.velum.ai.local.ModelStoreDir
import dev.velum.ai.pipeline.RecordingJobScheduler
import dev.velum.app.di.AppContainer
import dev.velum.core.data.DriverFactory
import dev.velum.core.security.InMemorySecureStore
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** #329: recreating the screen (rotation, theme, locale) cancels its coroutines; a share being imported must still land. */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class, sdk = [34])
class DetachedShareImportTest {
    @org.junit.After fun drainSharedChannel() = runBlocking {
        while (kotlinx.coroutines.withTimeoutOrNull(300) { dev.velum.app.platform.IncomingShare.next() } != null) Unit
    }

    @Test fun cancelling_the_collector_mid_normalize_still_creates_the_recording() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("velum.db")
        val root = ModelStoreDir(Files.createTempDirectory("velum-detached").toString())
        val container = AppContainer(
            InMemorySecureStore(), DriverFactory(context), modelStoreRoot = root,
            recordingJobSchedulerFactory = { _, _ -> RecordingJobScheduler { _, _, _, _ -> dev.velum.ai.pipeline.EnqueueResult.STARTED_OR_REPLACED } },
        )
        val dir = File(context.cacheDir, "detached-fixtures").apply { deleteRecursively(); mkdirs() }
        try {
            val wav = silentWav(File(dir, "share.wav"))
            val started = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val pipeline = AudioImportPipeline(container, normalize = { path ->
                started.complete(Unit)
                gate.await()
                dev.velum.app.platform.AudioNormalizer().normalizeToWav(path)
            })
            runBlocking {
                val collector = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { pipeline.importSharedDetached(wav.path) }
                withTimeout(30_000) { started.await() }
                collector.cancel() // the screen is recreated while normalize is running
                collector.join()
                gate.complete(Unit)
                var count = 0L
                withTimeout(60_000) {
                    while (count == 0L) {
                        count = container.repository.storageUsage().recordingCount
                        if (count == 0L) kotlinx.coroutines.delay(100)
                    }
                }
                assertEquals(1L, count, "the import must finish after the collector is cancelled")
            }
        } finally {
            container.databaseHandle.driver.close()
            root.deleteRecursively()
            dir.deleteRecursively()
        }
    }

    private fun silentWav(file: File): File {
        val samples = 16_000 / 5
        val buffer = java.nio.ByteBuffer.allocate(44 + samples * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(16_000).putInt(32_000).putShort(2).putShort(16)
        buffer.put("data".toByteArray()).putInt(samples * 2)
        repeat(samples) { buffer.putShort(0) }
        file.writeBytes(buffer.array())
        return file
    }

    private fun withContainer(enqueues: java.util.concurrent.atomic.AtomicInteger? = null, block: suspend (AppContainer, File) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("velum.db")
        val root = ModelStoreDir(Files.createTempDirectory("velum-detached2").toString())
        val container = AppContainer(
            InMemorySecureStore(), DriverFactory(context), modelStoreRoot = root,
            recordingJobSchedulerFactory = { _, _ -> RecordingJobScheduler { _, _, _, _ -> enqueues?.incrementAndGet(); dev.velum.ai.pipeline.EnqueueResult.STARTED_OR_REPLACED } },
        )
        val dir = File(context.cacheDir, "detached-fixtures2").apply { deleteRecursively(); mkdirs() }
        try {
            runBlocking { block(container, dir) }
        } finally {
            container.databaseHandle.driver.close()
            root.deleteRecursively()
            dir.deleteRecursively()
        }
    }

    @Test fun a_failure_after_the_collector_stops_is_still_there_for_the_next_collector() = withContainer { container, dir ->
        val gate = CompletableDeferred<Unit>()
        val pipeline = AudioImportPipeline(container, normalize = { gate.await(); error("provider went away") })
        container.pendingShare = pipeline.startSharedDetached(silentWav(File(dir, "bad.wav")).path)
        kotlinx.coroutines.coroutineScope {
            val firstCollector = launch(Dispatchers.Default) { container.pendingShare!!.await() }
            firstCollector.cancel() // ON_STOP cancels the collector
            firstCollector.join()
        }
        gate.complete(Unit)
        val pending = checkNotNull(container.pendingShare) { "the in-flight import was dropped with its collector" }
        assertIs<ImportResult.Failed>(withTimeout(30_000) { pending.await() }) // the next collector reports this once
    }

    @Test fun the_same_file_shared_twice_at_once_makes_one_recording_and_one_job() {
        val enqueues = java.util.concurrent.atomic.AtomicInteger()
        withContainer(enqueues) { container, dir ->
            val a = silentWav(File(dir, "one.wav"))
            val b = File(dir, "two.wav").also { a.copyTo(it) }
            // Two pipelines: the app recreates the pipeline per composition, so the lock must live on the container.
            val results = withTimeout(60_000) {
                listOf(
                    AudioImportPipeline(container).startSharedDetached(a.path),
                    AudioImportPipeline(container).startSharedDetached(b.path),
                ).map { it.await() }
            }
            assertEquals(1, results.count { it is ImportResult.Created }, results.toString())
            assertEquals(1, results.count { it is ImportResult.Duplicate }, results.toString())
            assertEquals(1L, container.repository.storageUsage().recordingCount)
            assertEquals(1, enqueues.get(), "processing must be enqueued once")
        }
    }

    @Test fun a_wipe_during_a_detached_import_stops_it_and_leaves_nothing() = withContainer { container, dir ->
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val pipeline = AudioImportPipeline(container, normalize = { path ->
            started.complete(Unit)
            gate.await()
            dev.velum.app.platform.AudioNormalizer().normalizeToWav(path)
        })
        val pending = pipeline.startSharedDetached(silentWav(File(dir, "wipe.wav")).path)
        withTimeout(30_000) { started.await() }
        val stopped = container.wipeGate.wiping(10.seconds) { allStopped -> allStopped }
        gate.complete(Unit)
        assertTrue(stopped, "the wipe must stop the detached import")
        val result = runCatching { withTimeout(30_000) { pending.await() } }
        assertTrue(result.exceptionOrNull() is kotlinx.coroutines.CancellationException || result.getOrNull() is ImportResult.Failed, result.toString())
        assertEquals(0L, container.repository.storageUsage().recordingCount)
    }

    @Test fun a_finished_share_import_clears_its_pending_marker() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        dev.velum.app.platform.installAppContext(context)
        context.getSharedPreferences("velum_pending_shares", Context.MODE_PRIVATE).edit().clear().commit()
        withContainer { container, dir ->
            val wav = silentWav(File(dir, "marked.wav"))
            val u = android.net.Uri.parse("content://test.provider/marked.wav")
            org.robolectric.Shadows.shadowOf(context.contentResolver).registerInputStream(u, java.io.ByteArrayInputStream(wav.readBytes()))
            val copy = checkNotNull(dev.velum.app.platform.IncomingShare.copyShare(context, u))
            assertEquals(listOf(copy), dev.velum.app.platform.IncomingShare.pendingPaths(context)) // marker present while pending
            AudioImportPipeline(container).startSharedDetached(copy).await()
            assertEquals(emptyList(), dev.velum.app.platform.IncomingShare.pendingPaths(context), "the marker must go with the import")
        }
    }

    private fun copyShare(context: Context, wav: File, name: String): String {
        val u = android.net.Uri.parse("content://test.provider/$name.wav")
        org.robolectric.Shadows.shadowOf(context.contentResolver).registerInputStream(u, java.io.ByteArrayInputStream(wav.readBytes()))
        return checkNotNull(dev.velum.app.platform.IncomingShare.copyShare(context, u))
    }

    private fun pendingEntry(context: Context): String? =
        context.getSharedPreferences("velum_pending_shares", Context.MODE_PRIVATE).getStringSet("pending", emptySet()).orEmpty().singleOrNull()

    private fun freshMarkers(context: Context) {
        dev.velum.app.platform.installAppContext(context)
        dev.velum.app.platform.IncomingShare.resetProcessMemory()
        context.getSharedPreferences("velum_pending_shares", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun the_attempt_is_counted_before_the_import_does_any_work() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        freshMarkers(context)
        withContainer { container, dir ->
            val started = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val copy = copyShare(context, silentWav(File(dir, "count.wav")), "count")
            val pipeline = AudioImportPipeline(container, normalize = { started.complete(Unit); gate.await(); error("never finishes") })
            val pending = pipeline.startSharedDetached(copy)
            withTimeout(30_000) { started.await() }
            assertTrue(pendingEntry(context)!!.split('|')[1] == "1", "the start must be counted while the import is still running: ${pendingEntry(context)}")
            gate.complete(Unit)
            runCatching { pending.await() }
        }
    }

    @Test fun failed_and_wipe_refused_imports_clear_their_marker() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        freshMarkers(context)
        withContainer { container, dir ->
            val failing = AudioImportPipeline(container, normalize = { error("bad audio") })
            val bad = copyShare(context, silentWav(File(dir, "bad.wav")), "bad")
            assertIs<ImportResult.Failed>(failing.startSharedDetached(bad).await())
            assertEquals(null, pendingEntry(context), "a failed import must clear its marker")

            val refused = copyShare(context, silentWav(File(dir, "refused.wav")), "refused")
            container.wipeGate.wiping(1.seconds) { assertIs<ImportResult.Failed>(AudioImportPipeline(container).startSharedDetached(refused).await()) }
            assertEquals(null, pendingEntry(context), "an import refused by a wipe must clear its marker")
        }
    }

    @Test fun a_cancelled_import_clears_its_marker() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        freshMarkers(context)
        withContainer { container, dir ->
            val started = CompletableDeferred<Unit>()
            val copy = copyShare(context, silentWav(File(dir, "cancel.wav")), "cancel")
            val pipeline = AudioImportPipeline(container, normalize = { started.complete(Unit); awaitCancellation() })
            val pending = pipeline.startSharedDetached(copy)
            withTimeout(30_000) { started.await() }
            pending.cancel()
            runCatching { pending.await() }
            withTimeout(30_000) { while (pendingEntry(context) != null) kotlinx.coroutines.delay(50) }
        }
    }

    @Test fun a_wipe_clears_pending_shares_before_it_deletes_anything() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        freshMarkers(context)
        withContainer { container, dir ->
            copyShare(context, silentWav(File(dir, "w.wav")), "w")
            assertTrue(pendingEntry(context) != null)
            // The database is gone, so the step that deletes rows throws: a process dying there is the case that matters.
            // The marker must already be cleared; if the clear came after the row deletion it would survive this.
            container.databaseHandle.driver.close()
            runCatching { container.wipeAllData() }
            assertEquals(null, pendingEntry(context), "a wipe that dies mid-way must not leave a share to be imported again")
        }
    }

    @Test fun a_recovered_share_deleted_after_its_marker_is_not_brought_back_but_an_older_deletion_is_ignored() {
        withContainer { container, dir ->
            val wav = silentWav(File(dir, "tomb.wav"))
            val hash = dev.velum.app.platform.sha256File(wav.path)
            val nowMs = System.currentTimeMillis()
            container.repository.setMeta(dev.velum.core.data.VelumRepository.IMPORT_DELETED_PREFIX + hash, (nowMs / 1000).toString())
            // The marker is older than the deletion: the user trashed it after the share: stay deleted.
            assertIs<ImportResult.Deleted>(AudioImportPipeline(container).importShared(wav.path, recoveredAtMs = nowMs - 60_000))
            assertEquals(0L, container.repository.storageUsage().recordingCount)
            // The marker is newer than the deletion: this is a fresh share of a once-deleted file: import it.
            assertIs<ImportResult.Created>(AudioImportPipeline(container).importShared(wav.path, recoveredAtMs = nowMs + 120_000))
            // Equal to the second: the deletion happened in the same second as the marker, so it counts (not older).
            container.repository.wipeAllUserData()
            container.repository.setMeta(dev.velum.core.data.VelumRepository.IMPORT_DELETED_PREFIX + hash, "1700000000")
            assertIs<ImportResult.Deleted>(AudioImportPipeline(container).importShared(wav.path, recoveredAtMs = 1_700_000_000_500))
            assertIs<ImportResult.Created>(AudioImportPipeline(container).importShared(wav.path, recoveredAtMs = 1_700_000_001_000))
            // A share that was never recovered ignores tombstones, as before.
            container.repository.wipeAllUserData()
            container.repository.setMeta(dev.velum.core.data.VelumRepository.IMPORT_DELETED_PREFIX + hash, (nowMs / 1000).toString())
            assertIs<ImportResult.Created>(AudioImportPipeline(container).importShared(wav.path))
        }
    }
}
