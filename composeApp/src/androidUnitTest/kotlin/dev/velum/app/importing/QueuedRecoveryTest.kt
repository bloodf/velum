package dev.velum.app.importing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.Operation
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.common.util.concurrent.ListenableFuture
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.impl.utils.futures.SettableFuture
import dev.velum.ai.pipeline.RecordingProcessWorker
import dev.velum.ai.pipeline.createWorkManagerRecordingJobScheduler
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.async
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
    private val enqueuedTemplates = CopyOnWriteArrayList<String?>()
    private val busy = CopyOnWriteArrayList<String>()
    private var enqueueFailure: Throwable? = null
    private var savedOperation: SettableFuture<Operation.State.SUCCESS>? = null
    private var beforeSubmit: (() -> Unit)? = null
    private var extraWorkId: java.util.UUID? = null
    private var submitRealBeforeDelay = false
    private val submitted = kotlinx.coroutines.CompletableDeferred<Unit>()

    private fun withContainer(realScheduler: Boolean = false, block: suspend (AppContainer, File) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("velum.db")
        enqueued.clear()
        enqueuedTemplates.clear()
        busy.clear()
        enqueueFailure = null
        savedOperation = null
        beforeSubmit = null
        extraWorkId = null
        submitRealBeforeDelay = false
        val workIds = CopyOnWriteArrayList<java.util.UUID>()
        var appJob: kotlinx.coroutines.Job? = null
        val executor = if (realScheduler) Executors.newSingleThreadExecutor() else null
        if (executor != null) {
            WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(executor).build())
        }
        val root = ModelStoreDir(Files.createTempDirectory("velum-queued").toString())
        val container = AppContainer(
            InMemorySecureStore(), DriverFactory(context), modelStoreRoot = root,
            recordingJobSchedulerFactory = { _, scope ->
                appJob = scope.coroutineContext[kotlinx.coroutines.Job]
                if (realScheduler) createWorkManagerRecordingJobScheduler(context) { manager, name, policy, request ->
                    beforeSubmit?.invoke()
                    val future = savedOperation
                    if (future == null) {
                        workIds += request.id
                        manager.enqueueUniqueWork(name, policy, request)
                    } else {
                        if (submitRealBeforeDelay) {
                            workIds += request.id
                            manager.enqueueUniqueWork(name, policy, request).result.get()
                        }
                        submitted.complete(Unit)
                        object : Operation {
                            override fun getState(): LiveData<Operation.State> = MutableLiveData()
                            override fun getResult(): ListenableFuture<Operation.State.SUCCESS> = future
                        }
                    }
                } else object : RecordingJobScheduler {
                    override fun enqueue(recordingId: String, templateId: String?, retranscribe: Boolean, apiModelOverride: dev.velum.core.settings.ApiModelOverride?) {
                        enqueueFailure?.let { throw it }
                        enqueued += recordingId
                        enqueuedTemplates += templateId
                    }
                    override fun hasActiveWork(recordingId: String) = recordingId in busy
                }
            },
        )
        runBlocking {
            container.settingsRepository.load()
            if (realScheduler) appJob!!.cancelAndJoin()
        }
        val dir = File(context.cacheDir, "queued-fixtures").apply { deleteRecursively(); mkdirs() }
        try {
            runBlocking { block(container, dir) }
        } finally {
            try {
                if (realScheduler) {
                    val manager = WorkManager.getInstance(context)
                    (workIds + listOfNotNull(extraWorkId)).distinct().forEach { id ->
                        manager.cancelWorkById(id).result.get()
                        assertTrue(manager.getWorkInfoById(id).get()?.state?.isFinished != false)
                    }
                    WorkManagerTestInitHelper.closeWorkDatabase()
                    androidx.work.impl.WorkManagerImpl.setDelegate(null)
                }
            } finally {
                executor?.shutdown()
                container.databaseHandle.driver.close()
                root.deleteRecursively()
                dir.deleteRecursively()
            }
        }
    }

    private suspend fun AppContainer.row(id: String, status: String, trashed: Long = 0, source: String = "import") = repository.insertRecording(
        id = id, sessionId = null, folderId = null, title = "Synthetic", source = source, deviceSerial = null,
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

    @Test fun asynchronous_enqueue_failure_after_import_marks_saved_row_failed() = withContainer(realScheduler = true) { container, dir ->
        val source = File(dir, "async.wav").apply { writeBytes(ByteArray(64) { it.toByte() }) }
        val wav = File(dir, "async-normalized.wav").apply { writeBytes(ByteArray(64)) }
        val operation = SettableFuture.create<Operation.State.SUCCESS>()
        savedOperation = operation
        val pipeline = AudioImportPipeline(container, normalize = { NormalizedAudio(wav.path, 16_000, 1_000, null, "async-hash") })
        kotlinx.coroutines.coroutineScope {
            val result = async(Dispatchers.Default) { pipeline.importFile(source.path) }
            val id = kotlinx.coroutines.withTimeout(5_000) {
                var found: String? = null
                while (found == null) {
                    found = container.repository.getMeta("import_sha256_async-hash")
                    if (found == null) kotlinx.coroutines.delay(10)
                }
                found
            }
            kotlinx.coroutines.withTimeout(5_000) { submitted.await() }
            assertEquals("queued", container.repository.getRecording(id)!!.status)
            operation.setException(IllegalStateException("enqueue failed"))
            assertIs<ImportResult.Failed>(result.await())
            assertEquals("failed", container.repository.getRecording(id)!!.status)
        }
    }

    @Test fun cancellation_after_submission_waits_for_acceptance_without_failing_row() = withContainer(realScheduler = true) { container, _ ->
        container.row("rec-cancel-after-submit", "queued")
        val operation = SettableFuture.create<Operation.State.SUCCESS>()
        savedOperation = operation
        submitRealBeforeDelay = true
        val context = ApplicationProvider.getApplicationContext<Context>()
        kotlinx.coroutines.coroutineScope {
            val job = launch(Dispatchers.Default) { container.enqueueSavedRecording("rec-cancel-after-submit") }
            kotlinx.coroutines.withTimeout(5_000) { submitted.await() }
            job.cancel()
            operation.set(Operation.SUCCESS)
            job.join()
            assertTrue(job.isCancelled)
            assertEquals("queued", container.repository.getRecording("rec-cancel-after-submit")!!.status)
            assertTrue(withContext(Dispatchers.IO) { RecordingProcessWorker.isWorkActive(context, "rec-cancel-after-submit") })
        }
    }

    @Test fun recovery_keeps_existing_work_even_when_autoflow_template_differs() = withContainer(realScheduler = true) { container, _ ->
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = WorkManager.getInstance(context)
        val id = "rec-real-keep-${java.util.UUID.randomUUID()}"
        container.row(id, "queued", source = "phone")
        container.repository.upsertAutoflow(
            id = "real-flow", name = "Phone", enabled = true, wakePhrase = null, durationMinSeconds = 0, durationMaxSeconds = 3600,
            sourceFilter = "phone", templateId = "velum.general", autoLabelVoice = false, language = "auto", notify = false,
        )
        val name = RecordingProcessWorker.UNIQUE_PREFIX + id
        var original: java.util.UUID? = null
        var submissionFailure: Throwable? = null
        beforeSubmit = {
            beforeSubmit = null
            try {
                RecordingProcessWorker.enqueue(context, id, "velum.meeting", true, null, awaitOperation = true)
                original = manager.getWorkInfosForUniqueWork(name).get().single().id
                extraWorkId = original
            } catch (failure: Throwable) {
                submissionFailure = failure
                throw failure
            }
        }
        val handed = withContext(Dispatchers.IO) { container.requeueStrandedRecordings() }
        submissionFailure?.let { throw AssertionError("Concurrent work submission failed", it) }
        assertEquals(1, handed)
        assertTrue(beforeSubmit == null, "recovery must reach WorkManager submission")
        val kept = requireNotNull(original)
        assertEquals(listOf(kept), manager.getWorkInfosForUniqueWork(name).get().map { it.id })
        assertEquals(0, withContext(Dispatchers.IO) { container.requeueStrandedRecordings() })
        assertEquals(listOf(kept), manager.getWorkInfosForUniqueWork(name).get().map { it.id })
        manager.cancelWorkById(kept).result.get()
        assertEquals(false, withContext(Dispatchers.IO) { RecordingProcessWorker.isWorkActive(context, id) })
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

    @Test fun a_row_that_already_has_work_is_left_alone() = withContainer { container, _ ->
        container.row("rec-busy", "queued")
        container.row("rec-idle", "queued")
        busy += "rec-busy"
        assertEquals(1, container.requeueStrandedRecordings())
        assertEquals(listOf("rec-idle"), enqueued.toList(), "a job that is running would have been replaced")
    }

    @Test fun startup_waits_for_the_settings_before_it_starts_any_job() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("velum.db")
        enqueued.clear()
        val storeOpen = kotlinx.coroutines.CompletableDeferred<Unit>()
        val inner = InMemorySecureStore()
        // The settings load reads the store first, so holding the store holds the load.
        val slowStore = object : dev.velum.core.security.SecureStore by inner {
            override suspend fun getString(key: String): String? { storeOpen.await(); return inner.getString(key) }
            override suspend fun contains(key: String): Boolean { storeOpen.await(); return inner.contains(key) }
        }
        val root = ModelStoreDir(Files.createTempDirectory("velum-queued").toString())
        val container = AppContainer(
            slowStore, DriverFactory(context), modelStoreRoot = root,
            recordingJobSchedulerFactory = { _, _ -> RecordingJobScheduler { id, _, _, _ -> enqueued += id } },
        )
        try {
            container.row("rec-queued", "queued")
            val requeue = async(kotlinx.coroutines.Dispatchers.Default) { container.requeueStrandedRecordings() }
            kotlinx.coroutines.delay(300)
            assertTrue(enqueued.isEmpty(), "a job was started on settings that were not loaded")
            storeOpen.complete(Unit)
            assertEquals(1, requeue.await())
            assertEquals(listOf("rec-queued"), enqueued.toList())
        } finally {
            container.databaseHandle.driver.close()
            root.deleteRecursively()
        }
    }

    @Test fun a_recovered_row_gets_the_template_of_the_flow_that_matches_it() = withContainer { container, _ ->
        container.repository.upsertAutoflow(
            id = "flow-phone", name = "Phone", enabled = true, wakePhrase = null, durationMinSeconds = 0, durationMaxSeconds = 3600,
            sourceFilter = "phone", templateId = "velum.general", autoLabelVoice = false, language = "auto", notify = false,
        )
        container.row("rec-phone", "queued", source = "phone")
        container.row("rec-import", "queued", source = "import")
        assertEquals(2, container.requeueStrandedRecordings())
        assertEquals(mapOf("rec-phone" to "velum.general", "rec-import" to null), enqueued.zip(enqueuedTemplates).toMap())
    }

    @Test fun a_cancelled_startup_leaves_the_row_queued_for_the_next_start() = withContainer { container, _ ->
        container.row("rec-queued", "queued")
        enqueueFailure = CancellationException("synthetic cancel")
        try {
            container.requeueStrandedRecordings()
        } catch (_: CancellationException) {
        }
        assertEquals("queued", container.repository.getRecording("rec-queued")!!.status)
    }
}
