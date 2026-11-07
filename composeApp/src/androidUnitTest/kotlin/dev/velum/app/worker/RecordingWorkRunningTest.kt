package dev.velum.app.worker

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
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
import dev.velum.ai.pipeline.RecordingProcessWorker
import dev.velum.ai.pipeline.RecordingProcessor
import dev.velum.core.data.DriverFactory
import dev.velum.core.data.VelumRepository
import dev.velum.core.data.createDatabase
import dev.velum.core.security.InMemorySecureStore
import dev.velum.core.settings.SettingsRepository
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #324 criterion 1 against a worker that is actually RUNNING in WorkManager's test driver (the queued-only cases are in
 * [RecordingWorkKeepTest]): an enqueue with the same template and model keeps it; a different template replaces it.
 * The job is a summary-only run whose LLM blocks until released, so it stays running while the second enqueue arrives.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class RecordingWorkRunningTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val handle = createDatabase(DriverFactory(context))
    private val repo = VelumRepository(handle)
    private val settings = SettingsRepository(InMemorySecureStore()).also { runBlocking { it.load() } }
    private val summaries = AtomicInteger()
    private val inLlm = CountDownLatch(1)
    private val gate = CompletableDeferred<Unit>()

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
        override val engineId = "blocking"
        override suspend fun complete(request: LlmRequest, modelId: String): LlmResult {
            summaries.incrementAndGet()
            inLlm.countDown()
            gate.await()
            return LlmResult(text = "## Summary\nok", engineId = engineId, modelId = modelId)
        }
    }

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(Executors.newFixedThreadPool(2)).build(),
        )
        AiRuntimeHolder.install(
            processor = RecordingProcessor(
                AiTaskRouter(
                    settings = MutableStateFlow(settings.settings.value),
                    localTranscriber = asr, apiTranscriber = asr, localDiarizer = diar, apiDiarizer = diar,
                    localLlm = llm, apiLlm = llm,
                ),
                repo,
                settings,
            ),
            repository = repo,
            settings = settings,
        )
        runBlocking {
            repo.insertRecording(
                id = "rec-running", sessionId = null, folderId = null, title = "T", source = "phone", deviceSerial = null,
                startedAt = 1, durationSeconds = 1, sizeBytes = 0, localPath = null, transcript = "hello",
                summaryMarkdown = null, status = "queued",
            )
        }
    }

    @After fun tearDown() {
        gate.complete(Unit)
        AiRuntimeHolder.processor = null
        AiRuntimeHolder.repository = null
        AiRuntimeHolder.settings = null
        handle.driver.close()
    }

    private fun infos(): List<WorkInfo> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(RecordingProcessWorker.UNIQUE_PREFIX + "rec-running").get()

    /** Off the main thread, as the runner and MicFinalize call it (the params lookup is skipped on main). */
    private fun enqueue(template: String): Boolean {
        var started = false
        val t = Thread { started = RecordingProcessWorker.enqueue(context, "rec-running", template, false, null) }
        t.start(); t.join()
        return started
    }

    private fun startRunningWork(): java.util.UUID {
        assertTrue(enqueue("velum.meeting"), "the first enqueue starts work")
        val id = infos().single().id
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(id)
        assertTrue(inLlm.await(30, TimeUnit.SECONDS), "the worker never reached the blocking summary")
        assertEquals(WorkInfo.State.RUNNING, infos().single { it.id == id }.state)
        return id
    }

    @Test fun anEnqueueWithTheSameTemplateKeepsTheRunningJob() {
        val first = startRunningWork()
        assertEquals(false, enqueue("velum.meeting"), "reports it started nothing")
        val after = infos()
        assertEquals(listOf(first), after.map { it.id }, "no second work item: $after")
        assertEquals(WorkInfo.State.RUNNING, after.single().state, "the running job was not cancelled")
        gate.complete(Unit)
        val deadline = System.currentTimeMillis() + 30_000
        while (infos().single().state != WorkInfo.State.SUCCEEDED && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(WorkInfo.State.SUCCEEDED, infos().single().state)
        assertEquals(1, summaries.get(), "the summary ran once")
    }

    @Test fun anEnqueueWithADifferentTemplateReplacesTheRunningJob() {
        val first = startRunningWork()
        assertEquals(true, enqueue("velum.summary"))
        // REPLACE cancels the running job and deletes its record: it is gone or CANCELLED, never still RUNNING.
        val deadline = System.currentTimeMillis() + 30_000
        while (infos().any { it.id == first && it.state == WorkInfo.State.RUNNING } && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val after = infos()
        assertTrue(after.none { it.id == first && it.state != WorkInfo.State.CANCELLED }, "the old job was replaced: $after")
        assertTrue(after.any { it.id != first && !it.state.isFinished }, "a replacement is queued or running: $after")
    }
}
