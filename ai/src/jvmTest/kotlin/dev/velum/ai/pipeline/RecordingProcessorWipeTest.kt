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
                llmRelease.await()
                return LlmResult(text = "## Synthetic summary", engineId = engineId, modelId = modelId)
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
}
