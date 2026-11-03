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
import dev.velum.core.data.createDatabase
import dev.velum.core.model.ComputeMode
import dev.velum.core.security.InMemorySecureStore
import dev.velum.core.settings.SettingsRepository
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking

class AskAcrossRecordingsTest {
    @Test
    fun askAcross_retrievesCitationsAndAnswers() = runBlocking {
        val repo = VelumRepository(createDatabase(DriverFactory()))
        val settings = SettingsRepository(InMemorySecureStore())
        settings.load()
        settings.setSummaryMode(ComputeMode.Local)
        settings.setOutputLanguage("en")

        val now = 1_700_000_100L
        repo.insertRecording(
            id = "rec-budget",
            sessionId = null,
            folderId = null,
            title = "Q4 budget review",
            source = "phone",
            deviceSerial = null,
            startedAt = now,
            durationSeconds = 1_200,
            sizeBytes = 0,
            localPath = null,
            transcript = """
                **Alex Rivera** · 0:12
                We agreed the marketing budget stays at 120 thousand for Q4.

                **Jordan Lee** · 0:45
                Finance can unlock the reserve if pipeline hits 2 million.
            """.trimIndent(),
            summaryMarkdown = "## Decisions\n- Keep marketing at 120k\n",
            status = "ready",
        )
        repo.insertRecording(
            id = "rec-hiring",
            sessionId = null,
            folderId = null,
            title = "Hiring sync",
            source = "phone",
            deviceSerial = null,
            startedAt = now - 86_400,
            durationSeconds = 900,
            sizeBytes = 0,
            localPath = null,
            transcript = """
                **Sam Patel** · 1:05
                Offer the senior Android role at 185k base.
            """.trimIndent(),
            summaryMarkdown = null,
            status = "ready",
        )

        val fakeLlm = object : LlmEngine {
            override val engineId = "fake"
            override suspend fun complete(request: LlmRequest, modelId: String): LlmResult {
                assertTrue(request.systemPrompt.contains("[1]"), request.systemPrompt)
                return LlmResult(
                    text = "Marketing budget remains **120k** for Q4 [1].",
                    engineId = engineId,
                    modelId = modelId,
                )
            }
        }
        val noopAsr = object : Transcriber {
            override val engineId = "noop"
            override suspend fun transcribe(input: AudioInput, modelId: String) =
                TranscriptionResult(text = "", segments = emptyList(), engineId = engineId, modelId = modelId)
        }
        val noopDiar = object : Diarizer {
            override val engineId = "noop"
            override suspend fun diarize(input: AudioInput, modelId: String) =
                DiarizationResult(spans = emptyList(), namedSpeakers = emptyMap(), engineId = engineId, modelId = modelId)
        }
        val router = AiTaskRouter(
            settings = MutableStateFlow(settings.settings.value),
            localTranscriber = noopAsr,
            apiTranscriber = noopAsr,
            localDiarizer = noopDiar,
            apiDiarizer = noopDiar,
            localLlm = fakeLlm,
            apiLlm = fakeLlm,
        )
        val ask = AskAcrossRecordings(router, repo, settings)
        val answer = ask.ask("What is the marketing budget for Q4?")
        assertTrue(answer.citations.isNotEmpty(), "expected citations")
        assertTrue(answer.markdown.contains("120k"), answer.markdown)
        assertTrue(answer.citations.any { it.title.contains("budget", ignoreCase = true) })
        assertTrue(answer.citations.first().timestampLabel.isNotBlank())
    }

    // #205: marker notes must not reach the model's prompt or drive retrieval.
    @Test
    fun markers_never_reach_the_prompt_or_retrieval() = runBlocking {
        val repo = VelumRepository(createDatabase(DriverFactory()))
        val settings = SettingsRepository(InMemorySecureStore())
        settings.load()
        settings.setSummaryMode(ComputeMode.Local)
        repo.insertRecording(
            id = "rec-m", sessionId = null, folderId = null, title = "Sync", source = "phone", deviceSerial = null,
            startedAt = 1_700_000_100L, durationSeconds = 60, sizeBytes = 0, localPath = null,
            transcript = "We discussed the roadmap.\n\n## Markers\n- **0:20** secretzebramarker",
            summaryMarkdown = null, status = "ready",
        )
        val prompts = mutableListOf<String>()
        val llm = object : LlmEngine {
            override val engineId = "fake"
            override suspend fun complete(request: LlmRequest, modelId: String): LlmResult {
                prompts += request.systemPrompt + "\n" + request.userPrompt
                return LlmResult(text = "ok", engineId = engineId, modelId = modelId)
            }
        }
        val asr = object : Transcriber {
            override val engineId = "noop"
            override suspend fun transcribe(input: AudioInput, modelId: String) =
                TranscriptionResult(text = "", segments = emptyList(), engineId = engineId, modelId = modelId)
        }
        val diar = object : Diarizer {
            override val engineId = "noop"
            override suspend fun diarize(input: AudioInput, modelId: String) =
                DiarizationResult(spans = emptyList(), namedSpeakers = emptyMap(), engineId = engineId, modelId = modelId)
        }
        val router = AiTaskRouter(MutableStateFlow(settings.settings.value), asr, asr, diar, diar, llm, llm)
        AskAcrossRecordings(router, repo, settings).ask("What did we discuss about the roadmap?")
        AskAiChat(router, repo, settings).ask("rec-m", "What was discussed?")
        assertTrue(prompts.isNotEmpty())
        assertTrue(prompts.none { it.contains("secretzebramarker") }, prompts.toString())
        assertTrue(prompts.any { it.contains("roadmap") })
    }
}
