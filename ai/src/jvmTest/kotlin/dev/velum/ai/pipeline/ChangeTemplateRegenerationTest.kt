package dev.velum.ai.pipeline

import dev.velum.ai.engine.AiTaskRouter
import dev.velum.ai.engine.AudioInput
import dev.velum.ai.engine.DiarizationResult
import dev.velum.ai.engine.Diarizer
import dev.velum.ai.engine.JobProgress
import dev.velum.ai.engine.LlmEngine
import dev.velum.ai.engine.LlmRequest
import dev.velum.ai.engine.LlmResult
import dev.velum.ai.engine.Transcriber
import dev.velum.ai.engine.TranscriptionResult
import dev.velum.core.data.DriverFactory
import dev.velum.core.data.VelumRepository
import dev.velum.core.data.createDatabase
import dev.velum.core.model.ComputeMode
import dev.velum.core.security.InMemorySecureStore
import dev.velum.core.settings.SettingsRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** #103: change template + regenerate from the existing transcript, versions preserved. */
class ChangeTemplateRegenerationTest {
    private val transcript = "Speaker 1: we agreed to ship on Friday.\nSpeaker 2: I will own the release notes."

    private class Rig(
        val repo: VelumRepository,
        val processor: RecordingProcessor,
        val local: RecordingLlm,
        val api: RecordingLlm,
    )

    private class RecordingLlm(val name: String) : LlmEngine {
        override val engineId = name
        val requests = mutableListOf<LlmRequest>()
        var failWith: Throwable? = null
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun complete(request: LlmRequest, modelId: String): LlmResult {
            requests += request
            gate?.await()
            failWith?.let { throw it }
            return LlmResult(text = "## $name\n${request.systemPrompt.hashCode()}", engineId = engineId, modelId = modelId)
        }
    }

    private val noopAsr = object : Transcriber {
        override val engineId = "noop-asr"
        override suspend fun transcribe(input: AudioInput, modelId: String): TranscriptionResult =
            error("ASR must not run when only the template changes")
    }
    private val noopDiar = object : Diarizer {
        override val engineId = "noop-diar"
        override suspend fun diarize(input: AudioInput, modelId: String): DiarizationResult =
            error("Diarization must not run when only the template changes")
    }

    private suspend fun rig(summaryMode: ComputeMode = ComputeMode.Local): Rig {
        val repo = VelumRepository(createDatabase(DriverFactory()))
        val settings = SettingsRepository(InMemorySecureStore()).also {
            it.load()
            it.setTranscriptionMode(ComputeMode.Local)
            it.setDiarizationMode(ComputeMode.Local)
            it.setSummaryMode(summaryMode)
            it.setApiKey("sk-test-not-real")
        }
        val local = RecordingLlm("local")
        val api = RecordingLlm("api")
        val router = AiTaskRouter(
            settings = MutableStateFlow(settings.settings.value),
            localTranscriber = noopAsr,
            apiTranscriber = noopAsr,
            localDiarizer = noopDiar,
            apiDiarizer = noopDiar,
            localLlm = local,
            apiLlm = api,
        )
        return Rig(repo, RecordingProcessor(router, repo, settings), local, api)
    }

    private suspend fun VelumRepository.seed(id: String, localPath: String? = null, text: String = transcript) {
        insertRecording(
            id = id, sessionId = null, folderId = null, title = "t", source = "phone", deviceSerial = null,
            startedAt = 1, durationSeconds = 60, sizeBytes = 0, localPath = localPath,
            transcript = text, summaryMarkdown = null, status = "ready",
        )
    }

    @Test
    fun regenerate_usesSelectedTemplateAndTranscript_andPreservesPreviousVersions() = runBlocking<Unit> {
        val r = rig()
        r.repo.seed("rec")
        assertIs<JobProgress.Succeeded>(r.processor.process("rec", "velum.meeting", retranscribe = false).last())
        val first = r.repo.getRecording("rec")!!.summary_markdown!!

        assertIs<JobProgress.Succeeded>(r.processor.process("rec", "velum.sales", retranscribe = false).last())
        val second = r.repo.getRecording("rec")!!.summary_markdown!!
        assertNotEquals(first, second)
        assertTrue(r.local.requests.last().userPrompt.contains("release notes"), "existing transcript is the input")
        assertNotEquals(r.local.requests[0].systemPrompt, r.local.requests[1].systemPrompt, "selected template drives the prompt")

        assertIs<JobProgress.Succeeded>(r.processor.process("rec", "velum.call", retranscribe = false).last())

        // Versions are persisted rows; they survive regeneration and activation changes.
        val versions = r.repo.getSummaryVersions("rec").sortedBy { it.created_at }
        assertEquals(3, versions.size)
        val byTemplate = versions.associateBy { it.template_id }
        assertEquals(first, byTemplate["velum.meeting"]!!.markdown, "first summary preserved with its template")
        assertEquals(second, byTemplate["velum.sales"]!!.markdown)
        assertEquals(1, versions.count { it.is_active != 0L })
        assertEquals("velum.call", versions.single { it.is_active != 0L }.template_id)
        assertTrue(versions.all { it.template_name.isNotBlank() })
    }

    @Test
    fun regenerate_doesNotNeedAudioOrAsr() = runBlocking<Unit> {
        val r = rig()
        r.repo.seed("rec", localPath = "/definitely/missing/audio.wav")
        assertIs<JobProgress.Succeeded>(r.processor.process("rec", "velum.meeting", retranscribe = false).last())
    }

    @Test
    fun failure_keepsPreviousSummaryAndVersions_andRecordsActionableError() = runBlocking<Unit> {
        val r = rig()
        r.repo.seed("rec")
        r.processor.process("rec", "velum.meeting", retranscribe = false).last()
        val before = r.repo.getRecording("rec")!!.summary_markdown
        r.local.failWith = IllegalStateException("model exploded")

        val progress = r.processor.process("rec", "velum.sales", retranscribe = false).last()

        assertIs<JobProgress.Failed>(progress)
        assertEquals(before, r.repo.getRecording("rec")!!.summary_markdown)
        assertEquals(1, r.repo.getSummaryVersions("rec").size)
        assertTrue(r.repo.getProcessingError("rec").orEmpty().contains("model exploded"))

        r.local.failWith = null
        assertIs<JobProgress.Succeeded>(r.processor.process("rec", "velum.sales", retranscribe = false).last())
        assertEquals(null, r.repo.getProcessingError("rec"), "error cleared after a successful retry")
    }

    @Test
    fun blankTranscript_failsWithoutTouchingPreviousSummary() = runBlocking<Unit> {
        val r = rig()
        r.repo.seed("rec")
        r.processor.process("rec", "velum.meeting", retranscribe = false).last()
        r.repo.updateTranscript("rec", "")
        val before = r.repo.getRecording("rec")!!.summary_markdown

        assertIs<JobProgress.Failed>(r.processor.process("rec", "velum.sales", retranscribe = false).last())
        assertEquals(before, r.repo.getRecording("rec")!!.summary_markdown)
        assertEquals(1, r.repo.getSummaryVersions("rec").size)
    }

    @Test
    fun markersOnlyTranscript_countsAsNoTextAndIsNeverSummarized() = runBlocking<Unit> {
        val r = rig()
        val markers = "## Markers\n- **1:05** Synthetic note\n"
        r.repo.seed("rec", text = markers)

        assertIs<JobProgress.Failed>(r.processor.process("rec", "velum.sales", retranscribe = false).last())

        assertTrue(r.local.requests.isEmpty(), "no summary from markers alone")
        assertEquals(markers, r.repo.getRecording("rec")!!.transcript)
    }

    @Test
    fun markersAreLeftOutOfTheSummaryPrompt() = runBlocking<Unit> {
        val r = rig()
        val stored = "$transcript\n\n## Markers\n- **1:05** Synthetic note\n"
        r.repo.seed("rec", text = stored)

        assertIs<JobProgress.Succeeded>(r.processor.process("rec", "velum.sales", retranscribe = false).last())

        val prompt = r.local.requests.last().userPrompt
        assertTrue(prompt.contains("release notes"))
        assertTrue(!prompt.contains("Synthetic note"))
        assertEquals(stored, r.repo.getRecording("rec")!!.transcript)
    }

    @Test
    fun cancellation_keepsPreviousSummary_andDoesNotLeaveRecordingGenerating() = runBlocking<Unit> {
        val r = rig()
        r.repo.seed("rec")
        r.processor.process("rec", "velum.meeting", retranscribe = false).last()
        val before = r.repo.getRecording("rec")!!.summary_markdown
        val gate = CompletableDeferred<Unit>()
        r.local.gate = gate

        val job = async(start = CoroutineStart.UNDISPATCHED) { r.processor.process("rec", "velum.sales", retranscribe = false).last() }
        withTimeout(5_000) { while (r.local.requests.size < 2) kotlinx.coroutines.delay(10) }
        job.cancelAndJoin()

        assertEquals(before, r.repo.getRecording("rec")!!.summary_markdown)
        assertEquals(1, r.repo.getSummaryVersions("rec").size)
        assertEquals("ready", r.repo.getRecording("rec")!!.status)
    }

    @Test
    fun cancellation_fromQueued_mapsToReadyWithSummary_andFailedWithout() = runBlocking<Unit> {
        for (withSummary in listOf(true, false)) {
            val r = rig()
            r.repo.seed("rec")
            if (withSummary) r.processor.process("rec", "velum.meeting", retranscribe = false).last()
            r.repo.updateStatus("rec", "queued")
            val gate = CompletableDeferred<Unit>()
            r.local.gate = gate
            val expectedRequests = if (withSummary) 2 else 1
            val job = async(start = CoroutineStart.UNDISPATCHED) { r.processor.process("rec", "velum.sales", retranscribe = false).last() }
            withTimeout(5_000) { while (r.local.requests.size < expectedRequests) kotlinx.coroutines.delay(10) }
            job.cancelAndJoin()
            assertEquals(if (withSummary) "ready" else "failed", r.repo.getRecording("rec")!!.status, "withSummary=$withSummary")
        }
    }

    @Test
    fun localSummaryMode_neverCallsApi_andApiModeNeverCallsLocal() = runBlocking<Unit> {
        val local = rig(ComputeMode.Local)
        local.repo.seed("rec")
        local.processor.process("rec", "velum.meeting", retranscribe = false).last()
        assertEquals(0, local.api.requests.size)
        assertEquals(1, local.local.requests.size)

        val api = rig(ComputeMode.Api)
        api.repo.seed("rec")
        assertIs<JobProgress.Succeeded>(api.processor.process("rec", "velum.meeting", retranscribe = false).last())
        assertEquals(0, api.local.requests.size)
        assertEquals(1, api.api.requests.size)
    }
}
