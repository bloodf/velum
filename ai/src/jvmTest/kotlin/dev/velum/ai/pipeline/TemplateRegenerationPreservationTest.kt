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
class TemplateRegenerationPreservationTest {
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
    fun failedAndCancelledRegenerationPreserveTheActiveVersionAndItsTemplate() = runBlocking<Unit> {
        val r = rig()
        r.repo.seed("rec")
        assertIs<JobProgress.Succeeded>(r.processor.process("rec", "velum.meeting", retranscribe = false).last())
        val before = r.repo.getSummaryVersions("rec").single()
        val summary = r.repo.getRecording("rec")!!.summary_markdown
        assertEquals("velum.meeting", before.template_id)
        assertEquals(1L, before.is_active)

        r.local.failWith = IllegalStateException("synthetic failure")
        assertIs<JobProgress.Failed>(r.processor.process("rec", "velum.sales", retranscribe = false).last())
        assertEquals(listOf(before), r.repo.getSummaryVersions("rec"))
        assertEquals(summary, r.repo.getRecording("rec")!!.summary_markdown)

        val statusBeforeCancellation = r.repo.getRecording("rec")!!.status
        assertEquals("failed", statusBeforeCancellation)

        r.local.failWith = null
        r.local.gate = CompletableDeferred()
        val job = async(start = CoroutineStart.UNDISPATCHED) {
            r.processor.process("rec", "velum.sales", retranscribe = false).last()
        }
        withTimeout(5_000) { while (r.local.requests.size < 3) kotlinx.coroutines.delay(10) }
        job.cancelAndJoin()
        assertEquals(listOf(before), r.repo.getSummaryVersions("rec"))
        assertEquals(summary, r.repo.getRecording("rec")!!.summary_markdown)
        assertEquals(statusBeforeCancellation, r.repo.getRecording("rec")!!.status)
    }
}
