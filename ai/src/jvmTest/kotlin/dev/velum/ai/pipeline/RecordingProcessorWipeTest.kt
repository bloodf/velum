package dev.velum.ai.pipeline

import dev.velum.ai.engine.AiTaskRouter
import dev.velum.ai.engine.AudioInput
import dev.velum.ai.engine.DiarizationResult
import dev.velum.ai.engine.Diarizer
import dev.velum.ai.engine.LlmEngine
import dev.velum.ai.engine.LlmRequest
import dev.velum.ai.engine.LlmResult
import dev.velum.ai.engine.Transcriber
import dev.velum.ai.engine.TranscriptionResult
import dev.velum.core.data.DriverFactory
import dev.velum.core.data.VelumRepository
import dev.velum.core.data.WipeGate
import dev.velum.core.data.createDatabase
import dev.velum.core.model.ComputeMode
import dev.velum.core.security.InMemorySecureStore
import dev.velum.core.settings.SettingsRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Processing must not write anything for a wiped recording, whether it was running or queued. */
class RecordingProcessorWipeTest {
    private val repo = VelumRepository(createDatabase(DriverFactory()))
    private val gate = WipeGate()
    private val llmStarted = CompletableDeferred<Unit>()
    private val llmRelease = CompletableDeferred<Unit>()
    private val cleanupStarted = CompletableDeferred<Unit>()
    private val cleanupRelease = CompletableDeferred<Unit>()
    private var holdCleanup = false

    @Test fun a_job_queued_before_a_wipe_records_nothing_after_it() = runBlocking {
        processor().process("wiped-recording", retranscribe = false).collect()
        assertNull(repo.getProcessingError("wiped-recording"), "an error was recorded for a recording that is gone")
    }

    @Test fun a_wipe_stops_a_running_job_before_it_saves() = runBlocking {
        repo.insertRecording(
            id = "rec-running", sessionId = null, folderId = null, title = "Synthetic", source = "phone",
            deviceSerial = null, startedAt = 1_700_000_000L, durationSeconds = 60, sizeBytes = 0, localPath = null,
            transcript = "**Ana** · 0:00\nSynthetic transcript.", summaryMarkdown = null, status = "ready",
        )
        val processor = processor()
        val job = launch(Dispatchers.Default) { processor.process("rec-running", retranscribe = false).collect() }
        llmStarted.await()
        val stopped = gate.wiping(5.seconds) { it }
        llmRelease.complete(Unit)
        job.join()
        assertTrue(stopped)
        assertEquals(emptyList(), repo.getSummaryVersions("rec-running"), "the job saved a summary after the wipe began")
    }

    private suspend fun processor(): RecordingProcessor {
        val settings = SettingsRepository(InMemorySecureStore())
        settings.load()
        settings.setTranscriptionMode(ComputeMode.Local)
        settings.setDiarizationMode(ComputeMode.Local)
        settings.setSummaryMode(ComputeMode.Local)
        val llm = object : LlmEngine {
            override val engineId = "fake-llm"
            override suspend fun complete(request: LlmRequest, modelId: String): LlmResult {
                llmStarted.complete(Unit)
                try {
                    llmRelease.await()
                    return LlmResult(text = "## Synthetic summary", engineId = engineId, modelId = modelId)
                } finally {
                    if (holdCleanup) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        cleanupStarted.complete(Unit)
                        cleanupRelease.await()
                    }
                }
            }
        }
        val asr = object : Transcriber {
            override val engineId = "noop-asr"
            override suspend fun transcribe(input: AudioInput, modelId: String) =
                TranscriptionResult(text = "", engineId = engineId, modelId = modelId)
        }
        val diar = object : Diarizer {
            override val engineId = "noop-diar"
            override suspend fun diarize(input: AudioInput, modelId: String) =
                DiarizationResult(spans = emptyList(), engineId = engineId, modelId = modelId)
        }
        val router = AiTaskRouter(
            settings = kotlinx.coroutines.flow.MutableStateFlow(settings.settings.value),
            localTranscriber = asr, apiTranscriber = asr, localDiarizer = diar, apiDiarizer = diar,
            localLlm = llm, apiLlm = llm,
        )
        return RecordingProcessor(router, repo, settings, wipeGate = gate)
    }

    private suspend fun insertRunning(id: String) = repo.insertRecording(
        id = id, sessionId = null, folderId = null, title = "Synthetic", source = "phone",
        deviceSerial = null, startedAt = 1_700_000_000L, durationSeconds = 60, sizeBytes = 0, localPath = null,
        transcript = "**Ana** · 0:00\nSynthetic transcript.", summaryMarkdown = null, status = "ready",
    )

    @Test fun deleting_a_recording_stops_its_run_and_not_its_collector() = runBlocking {
        insertRunning("rec-running")
        val processor = processor()
        var continued = false
        val job = launch(Dispatchers.Default) {
            processor.process("rec-running", retranscribe = false).collect()
            continued = true
        }
        llmStarted.await()
        assertTrue(processor.stopRuns("rec-running", 5.seconds), "the run did not stop")
        llmRelease.complete(Unit)
        job.join()
        assertTrue(continued, "the collector was cancelled too")
        assertEquals(emptyList(), repo.getSummaryVersions("rec-running"), "the stopped run saved a summary")
    }

    @Test fun stopping_one_recording_leaves_another_running() = runBlocking {
        insertRunning("rec-kept")
        val processor = processor()
        val job = launch(Dispatchers.Default) { processor.process("rec-kept", retranscribe = false).collect() }
        llmStarted.await()
        assertTrue(processor.stopRuns("rec-other", 1.seconds))
        assertTrue(job.isActive, "stopping another recording stopped this run")
        llmRelease.complete(Unit)
        job.join()
        assertEquals(1, repo.getSummaryVersions("rec-kept").size)
    }

    @Test fun deletion_fence_refuses_direct_processing() = runBlocking {
        insertRunning("rec-fenced")
        val processor = processor()
        assertTrue(processor.beginDeletion("rec-fenced"))
        try {
            processor.process("rec-fenced", retranscribe = false).collect()
            assertTrue(!llmStarted.isCompleted)
            assertTrue(processor.stopRuns("rec-fenced", 1.seconds))
        } finally {
            processor.endDeletion("rec-fenced")
        }
    }

    @Test fun scheduler_rejects_during_deletion_then_uses_the_remaining_row() = runBlocking {
        val processor = processor()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
        val submitted = mutableListOf<String>()
        val delegate = RecordingJobScheduler { id, _, _, _ ->
            submitted.add(id)
            EnqueueResult.STARTED_OR_REPLACED
        }
        val scheduler = admittingRecordingJobScheduler(processor, scope, delegate)
        try {
            for (removeRow in listOf(false, true)) {
                val id = if (removeRow) "rec-deleted" else "rec-retained"
                insertRunning(id)
                val fenced = CompletableDeferred<Unit>()
                val finishDeletion = CompletableDeferred<Unit>()
                val deletion = launch(Dispatchers.Default) {
                    assertTrue(processor.beginDeletion(id))
                    try {
                        scheduler.cancel(id)
                        assertTrue(processor.stopRuns(id, 1.seconds))
                        fenced.complete(Unit)
                        finishDeletion.await()
                        // The retained-row case models fence release without simulating a deletion failure.
                        if (removeRow) repo.deleteRecordingHard(id)
                    } finally {
                        processor.endDeletion(id)
                    }
                }
                try {
                    kotlinx.coroutines.withTimeout(5_000) { fenced.await() }
                    assertEquals(EnqueueResult.REJECTED, scheduler.enqueueSaved(id, null, false, null))
                    assertTrue(repo.getRecording(id) != null)
                } finally {
                    finishDeletion.complete(Unit)
                    deletion.join()
                }
                val result = scheduler.enqueueSaved(id, null, false, null)
                assertEquals(if (removeRow) EnqueueResult.REJECTED else EnqueueResult.STARTED_OR_REPLACED, result)
                assertEquals(!removeRow, repo.getRecording(id) != null)
            }
            assertEquals(listOf("rec-retained"), submitted)
        } finally {
            scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
        }
    }

    @Test fun cancelled_collector_stays_registered_until_cleanup_finishes() = runBlocking {
        insertRunning("rec-cleanup")
        holdCleanup = true
        val processor = processor()
        val job = launch(Dispatchers.Default) { processor.process("rec-cleanup", retranscribe = false).collect() }
        try {
            llmStarted.await()
            job.cancel()
            cleanupStarted.await()
            assertTrue(!processor.stopRuns("rec-cleanup", 100.milliseconds))
        } finally { cleanupRelease.complete(Unit); job.cancel() }
        job.join()
        assertTrue(processor.stopRuns("rec-cleanup", 1.seconds))
    }

    @Test fun the_coroutine_scheduler_cancels_one_recordings_job() = runBlocking {
        insertRunning("rec-queued")
        val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Default)
        val scheduler = createCoroutineRecordingJobScheduler(processor(), scope)
        scheduler.enqueue("rec-queued", null, false, null)
        llmStarted.await()
        scheduler.cancel("rec-queued")
        llmRelease.complete(Unit)
        scope.coroutineContext[kotlinx.coroutines.Job]!!.children.forEach { it.join() }
        assertEquals(emptyList(), repo.getSummaryVersions("rec-queued"), "the cancelled job saved a summary")
    }
}
