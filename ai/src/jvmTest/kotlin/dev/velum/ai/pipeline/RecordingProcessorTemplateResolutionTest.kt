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
import dev.velum.core.templates.PlaudTemplateAliases
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking

/**
 * #99: an exact template row wins over alias resolution. Editing a builtin keeps its id and
 * stores builtin = 0, so a user edit of a later-removed duplicate id must still be used.
 */
class RecordingProcessorTemplateResolutionTest {
    @Test
    fun userEditedRowWithRemovedId_winsOverAliasSurvivor() = runBlocking {
        val (removed, survivor) = PlaudTemplateAliases.RemovedDuplicates.entries.first()
        val repo = VelumRepository(createDatabase(DriverFactory()))
        repo.upsertTemplate(survivor, "Survivor", "meeting", "", "SURVIVOR PROMPT", builtin = true)
        repo.upsertTemplate(removed, "My edit", "meeting", "", "USER EDIT MARKER")
        val captured = mutableListOf<LlmRequest>()
        val processor = processor(repo, captured)
        repo.insertRecording(
            id = "rec-edit",
            sessionId = null,
            folderId = null,
            title = "probe",
            source = "phone",
            deviceSerial = null,
            startedAt = 1_700_000_000L,
            durationSeconds = 60,
            sizeBytes = 0,
            localPath = null,
            transcript = "**Ana** · 0:00\nVamos alinhar o orçamento.",
            summaryMarkdown = null,
            status = "ready",
        )

        val progress = processor.process("rec-edit", templateId = removed, retranscribe = false).last()

        assertIs<JobProgress.Succeeded>(progress)
        val sys = captured.single().systemPrompt
        assertContains(sys, "USER EDIT MARKER")
        assertFalse(sys.contains("SURVIVOR PROMPT"), sys)
        val version = repo.getSummaryVersions("rec-edit").single()
        assertEquals(removed, version.template_id)
        assertEquals("My edit", version.template_name)
    }

    private suspend fun processor(repo: VelumRepository, captured: MutableList<LlmRequest>): RecordingProcessor {
        val settings = SettingsRepository(InMemorySecureStore())
        settings.load()
        settings.setTranscriptionMode(ComputeMode.Local)
        settings.setDiarizationMode(ComputeMode.Local)
        settings.setSummaryMode(ComputeMode.Local)
        val llm = object : LlmEngine {
            override val engineId = "fake-llm"
            override suspend fun complete(request: LlmRequest, modelId: String): LlmResult {
                captured += request
                return LlmResult(text = "## Resumo\n- ok", engineId = engineId, modelId = modelId)
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
            settings = MutableStateFlow(settings.settings.value),
            localTranscriber = asr,
            apiTranscriber = asr,
            localDiarizer = diar,
            apiDiarizer = diar,
            localLlm = llm,
            apiLlm = llm,
        )
        return RecordingProcessor(router, repo, settings)
    }
}
