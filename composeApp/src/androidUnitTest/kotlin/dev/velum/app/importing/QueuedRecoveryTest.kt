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
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** #415: a row saved as queued either gets its job or does not stay queued for a job that does not exist. */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class, sdk = [34])
class QueuedRecoveryTest {
    private val enqueued = CopyOnWriteArrayList<String>()
    private var enqueueFailure: Throwable? = null

    private fun withContainer(block: suspend (AppContainer, File) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("velum.db")
        enqueued.clear()
        enqueueFailure = null
        val root = ModelStoreDir(Files.createTempDirectory("velum-queued").toString())
        val container = AppContainer(
            InMemorySecureStore(), DriverFactory(context), modelStoreRoot = root,
            recordingJobSchedulerFactory = { _, _ ->
                RecordingJobScheduler { id, _, _, _ -> enqueueFailure?.let { throw it }; enqueued += id }
            },
        )
        val dir = File(context.cacheDir, "queued-fixtures").apply { deleteRecursively(); mkdirs() }
        try {
            runBlocking { block(container, dir) }
        } finally {
            container.databaseHandle.driver.close()
            root.deleteRecursively()
            dir.deleteRecursively()
        }
    }

    private suspend fun AppContainer.row(id: String, status: String, trashed: Long = 0) = repository.insertRecording(
        id = id, sessionId = null, folderId = null, title = "Synthetic", source = "import", deviceSerial = null,
        startedAt = 1, durationSeconds = 1, sizeBytes = 0, localPath = "/synthetic/$id.wav", transcript = null,
        summaryMarkdown = null, status = status, trashed = trashed,
    )

    @Test fun an_import_whose_enqueue_fails_leaves_a_failed_row_with_retry_not_a_queued_one() = withContainer { container, dir ->
        val source = File(dir, "synthetic.wav").apply { writeBytes(ByteArray(64) { it.toByte() }) }
        val wav = File(dir, "normalized.wav").apply { writeBytes(ByteArray(64)) }
        enqueueFailure = IllegalStateException("synthetic scheduler failure")
        val pipeline = AudioImportPipeline(container, normalize = { NormalizedAudio(wav.path, 16_000, 1_000, null, "synthetic-content-hash") })
        assertIs<ImportResult.Failed>(pipeline.importFile(source.path))
        val id = container.repository.getMeta("import_sha256_synthetic-content-hash")!!
        assertEquals("failed", container.repository.getRecording(id)!!.status)
    }

    @Test fun a_cancelled_enqueue_does_not_leave_the_row_queued() = withContainer { container, _ ->
        container.row("rec-1", "queued")
        enqueueFailure = CancellationException("synthetic cancel")
        try {
            container.enqueueSavedRecording("rec-1")
        } catch (_: CancellationException) {
        }
        assertEquals("failed", container.repository.getRecording("rec-1")!!.status)
    }

    @Test fun a_failed_enqueue_does_not_overwrite_a_row_a_job_already_moved_on() = withContainer { container, _ ->
        container.row("rec-1", "generating")
        enqueueFailure = IllegalStateException("synthetic scheduler failure")
        runCatching { container.enqueueSavedRecording("rec-1") }
        assertEquals("generating", container.repository.getRecording("rec-1")!!.status)
    }

    @Test fun startup_hands_every_queued_row_to_the_scheduler_and_only_those() = withContainer { container, _ ->
        container.row("rec-queued", "queued")
        container.row("rec-ready", "ready")
        container.row("rec-failed", "failed")
        container.row("rec-trashed", "queued", trashed = 1)
        assertEquals(1, container.requeueStrandedRecordings())
        assertEquals(listOf("rec-queued"), enqueued.toList())
    }

    @Test fun startup_marks_a_row_failed_when_its_job_still_cannot_be_enqueued() = withContainer { container, _ ->
        container.row("rec-queued", "queued")
        enqueueFailure = IllegalStateException("synthetic scheduler failure")
        assertEquals(0, container.requeueStrandedRecordings())
        assertEquals("failed", container.repository.getRecording("rec-queued")!!.status)
    }

    @Test fun startup_creates_no_work_while_a_wipe_runs() = withContainer { container, _ ->
        container.row("rec-queued", "queued")
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        var handed = -1
        kotlinx.coroutines.coroutineScope {
            val wipe = launch { container.wipeGate.wiping(5.seconds) { started.complete(Unit); release.await() } }
            started.await()
            handed = container.requeueStrandedRecordings()
            release.complete(Unit)
            wipe.join()
        }
        assertEquals(0, handed)
        assertTrue(enqueued.isEmpty(), "work was created during a wipe")
    }
}
