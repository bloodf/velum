package dev.velum.app.worker

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.impl.utils.futures.SettableFuture
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.common.util.concurrent.ListenableFuture
import dev.velum.ai.engine.AiTaskRouter
import dev.velum.ai.engine.AudioInput
import dev.velum.ai.engine.DiarizationResult
import dev.velum.ai.engine.Diarizer
import dev.velum.ai.engine.LlmEngine
import dev.velum.ai.engine.LlmRequest
import dev.velum.ai.engine.LlmResult
import dev.velum.ai.engine.Transcriber
import dev.velum.ai.engine.TranscriptionResult
import dev.velum.ai.pipeline.AiRuntimeHolder
import dev.velum.ai.pipeline.PROCESSING_STOPPED_MESSAGE
import dev.velum.ai.pipeline.RecordingProcessor
import dev.velum.ai.pipeline.RecordingProcessWorker
import dev.velum.core.data.DriverFactory
import dev.velum.core.data.VelumRepository
import dev.velum.core.data.createDatabase
import dev.velum.core.security.InMemorySecureStore
import dev.velum.core.settings.SettingsRepository
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #330: a refused foreground start (background start restriction) must not fail the work permanently. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class RecordingProcessWorkerForegroundTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val graphs = mutableListOf<Graph>()

    @After fun reset() {
        AiRuntimeHolder.processor = null
        AiRuntimeHolder.repository = null
        AiRuntimeHolder.settings = null
        graphs.forEach { it.close() }
        graphs.clear()
    }

    private fun worker(
        attempt: Int,
        refuse: Boolean = true,
        id: UUID = UUID.randomUUID(),
        recording: String = "rec-synthetic",
    ): RecordingProcessWorker =
        TestListenableWorkerBuilder<RecordingProcessWorker>(context)
            .setId(id)
            .setInputData(
                androidx.work.workDataOf(
                    RecordingProcessWorker.KEY_RECORDING_ID to recording,
                    RecordingProcessWorker.KEY_RETRANSCRIBE to false,
                ),
            )
            .setRunAttemptCount(attempt)
            .setForegroundUpdater { _, _: UUID, _: ForegroundInfo ->
                SettableFuture.create<Void>().also {
                    if (refuse) {
                        it.setException(IllegalStateException("startForegroundService not allowed (synthetic)"))
                    } else {
                        it.set(null)
                    }
                }
            }
            .build()

    @Test fun a_refused_foreground_start_retries_instead_of_failing_the_work() {
        // Holder not installed yet: the retry path after setForeground, which a thrown setForeground never reached.
        val result = runBlocking { worker(attempt = 0).doWork() }
        assertEquals(ListenableWorker.Result.retry(), result)
    }

    @Test fun a_refused_foreground_start_still_ends_in_the_normal_terminal_failure() {
        val result = runBlocking { worker(attempt = RecordingProcessWorker.MAX_HOLDER_RETRIES).doWork() }
        assertTrue(result is ListenableWorker.Result.Failure, "got $result")
    }

    // --- Worker level, with a real repository and processor (fake engines), foreground start refused ---

    private class Graph(context: Context, val onSummarize: suspend (VelumRepository) -> Unit = {}) {
        private val handle = createDatabase(DriverFactory(context))
        val repo = VelumRepository(handle)
        val settings = SettingsRepository(InMemorySecureStore()).also { runBlocking { it.load() } }
        private val asr = object : Transcriber {
            override val engineId = "asr"
            override suspend fun transcribe(input: AudioInput, modelId: String) =
                TranscriptionResult(text = "hello", engineId = engineId, modelId = modelId)
        }
        private val diar = object : Diarizer {
            override val engineId = "noop"
            override suspend fun diarize(input: AudioInput, modelId: String) =
                DiarizationResult(spans = emptyList(), engineId = engineId, modelId = modelId)
        }
        private val llm = object : LlmEngine {
            override val engineId = "noop"
            override suspend fun complete(request: LlmRequest, modelId: String): LlmResult {
                onSummarize(repo)
                return LlmResult(text = "## Summary\nok", engineId = engineId, modelId = modelId)
            }
        }
        val processor = RecordingProcessor(
            AiTaskRouter(
                settings = MutableStateFlow(settings.settings.value),
                localTranscriber = asr, apiTranscriber = asr, localDiarizer = diar, apiDiarizer = diar,
                localLlm = llm, apiLlm = llm,
            ),
            repo,
            settings,
        )

        fun close() = handle.driver.close()

        fun install() {
            AiRuntimeHolder.processor = processor
            AiRuntimeHolder.repository = repo
            AiRuntimeHolder.settings = settings
        }

        suspend fun insert(id: String) = repo.insertRecording(
            id = id, sessionId = null, folderId = null, title = "T", source = "phone", deviceSerial = null,
            startedAt = 1, durationSeconds = 1, sizeBytes = 0, localPath = null, transcript = "hello",
            summaryMarkdown = null, status = "queued",
        )
    }

    @Test fun a_refused_start_sets_the_flag_while_processing_and_clears_it_when_the_job_ends() {
        var seenDuring: String? = null
        val graph = Graph(context) { repo -> seenDuring = repo.unpromotedRunOwner("rec-w") }.also { graphs += it }
        runBlocking { graph.insert("rec-w") }
        graph.install()
        val id = UUID.randomUUID()
        val result = runBlocking { worker(attempt = 0, id = id, recording = "rec-w").doWork() }
        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(id.toString(), seenDuring, "the unpromoted run is recorded under its work id while it runs")
        runBlocking {
            assertEquals("ready", graph.repo.getRecording("rec-w")?.status)
            assertEquals(null, graph.repo.unpromotedRunOwner("rec-w"), "a normal end clears the flag")
        }
    }

    @Test fun a_rerun_of_the_same_stopped_unpromoted_work_fails_the_row_with_a_message() {
        val graph = Graph(context).also { graphs += it }
        runBlocking { graph.insert("rec-w") }
        graph.install()
        val id = UUID.randomUUID()
        runBlocking { graph.repo.setUnpromotedRun("rec-w", id.toString()) } // the earlier run was stopped mid-job
        val result = runBlocking { worker(attempt = 1, id = id, recording = "rec-w").doWork() }
        assertTrue(result is ListenableWorker.Result.Failure, "got $result")
        runBlocking {
            assertEquals("failed", graph.repo.getRecording("rec-w")?.status)
            assertEquals(
                PROCESSING_STOPPED_MESSAGE,
                graph.repo.getProcessingError("rec-w"),
            )
        }
    }

    @Test fun a_replacing_enqueue_with_a_new_work_id_is_not_a_rerun() {
        val graph = Graph(context).also { graphs += it }
        runBlocking { graph.insert("rec-w") }
        graph.install()
        runBlocking { graph.repo.setUnpromotedRun("rec-w", UUID.randomUUID().toString()) } // the cancelled predecessor
        val result = runBlocking { worker(attempt = 0, recording = "rec-w").doWork() }
        assertEquals(ListenableWorker.Result.success(), result)
        runBlocking {
            assertEquals("ready", graph.repo.getRecording("rec-w")?.status)
            assertEquals(null, graph.repo.unpromotedRunOwner("rec-w"), "the replacement cleared its own flag")
        }
    }

    @Test fun a_run_never_clears_a_flag_a_newer_run_took_over() {
        val newer = UUID.randomUUID().toString()
        // While this run summarizes, a newer unpromoted run records itself under its own work id.
        val graph = Graph(context) { repo -> repo.setUnpromotedRun("rec-w", newer) }.also { graphs += it }
        runBlocking { graph.insert("rec-w") }
        graph.install()
        val result = runBlocking { worker(attempt = 0, recording = "rec-w").doWork() }
        assertEquals(ListenableWorker.Result.success(), result)
        runBlocking { assertEquals(newer, graph.repo.unpromotedRunOwner("rec-w")) }
    }
}
