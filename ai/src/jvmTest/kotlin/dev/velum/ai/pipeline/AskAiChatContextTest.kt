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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking

/** Ask AI must fit the model's context: the on-device LLM failed on the whole transcript of a long recording. */
class AskAiChatContextTest {
    private val prompts = mutableListOf<LlmRequest>()

    private fun paragraph(i: Int, text: String = "we talked about the weather and the office plants again") =
        "**Speaker ${i % 3 + 1}** · ${i / 60}:${(i % 60).toString().padStart(2, '0')}\n$text number $i."

    private fun longTranscript(needleAt: Int) = (0 until 400).joinToString("\n\n") {
        if (it == needleAt) paragraph(it, "the zebrafund budget was approved at forty thousand") else paragraph(it)
    }

    private suspend fun ask(mode: ComputeMode, transcript: String, question: String, history: List<AskAiChat.Turn> = emptyList()): LlmRequest {
        val repo = VelumRepository(createDatabase(DriverFactory()))
        val settings = SettingsRepository(InMemorySecureStore())
        settings.load()
        settings.setSummaryMode(ComputeMode.Local)
        repo.insertRecording(
            id = "rec", sessionId = null, folderId = null, title = "Long meeting", source = "phone", deviceSerial = null,
            startedAt = 1_700_000_000L, durationSeconds = 3_600, sizeBytes = 0, localPath = null,
            transcript = transcript, summaryMarkdown = "## Summary\n- short", status = "ready",
        )
        val llm = object : LlmEngine {
            override val engineId = "fake"
            override suspend fun complete(request: LlmRequest, modelId: String): LlmResult {
                prompts += request
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
        val cfg = settings.settings.value.copy(summaryMode = mode)
        val router = AiTaskRouter(MutableStateFlow(cfg), asr, asr, diar, diar, llm, llm)
        // The chat reads the mode from the repository; API mode needs a key to pass its guard.
        if (mode == ComputeMode.Api) {
            settings.setSummaryMode(ComputeMode.Api)
            settings.setApiKey("sk-test")
        }
        AskAiChat(router, repo, settings).ask("rec", question, history)
        return prompts.last()
    }

    private fun transcriptSection(request: LlmRequest) =
        request.systemPrompt.substringAfter("--- Transcript ---\n").substringBefore("\n\n--- Summary ---")

    @Test fun a_transcript_that_fits_is_sent_whole() = runBlocking {
        val short = (0 until 5).joinToString("\n\n") { paragraph(it) }
        val request = ask(ComputeMode.Local, short, "What about the plants?")
        assertEquals(short, transcriptSection(request))
    }

    @Test fun a_long_transcript_on_device_sends_the_relevant_excerpts_within_budget() = runBlocking {
        val transcript = longTranscript(needleAt = 350)
        assertTrue(transcript.length > AskContextBudget.LOCAL.transcriptChars * 2)
        val request = ask(ComputeMode.Local, transcript, "What was the zebrafund budget?")
        val section = transcriptSection(request)
        assertTrue(section.length <= AskContextBudget.LOCAL.transcriptChars, "section ${section.length} chars")
        assertTrue(section.startsWith(AskContextBudget.EXCERPT_NOTE))
        assertTrue("zebrafund budget was approved" in section, "the matching paragraph must be kept")
    }

    @Test fun excerpts_keep_the_transcript_order() {
        val transcript = (0 until 200).joinToString("\n\n") {
            when (it) {
                20 -> "alpha topic first"
                180 -> "alpha topic second"
                else -> paragraph(it)
            }
        }
        val out = AskContextBudget.transcriptFor(transcript, "alpha topic", 2_000)
        assertTrue(out.indexOf("alpha topic first") in 0 until out.indexOf("alpha topic second"))
    }

    @Test fun without_any_matching_word_the_start_of_the_transcript_is_kept() {
        val transcript = longTranscript(needleAt = -1)
        val out = AskContextBudget.transcriptFor(transcript, "xyzzy?", 1_000)
        assertTrue(out.length <= 1_000)
        assertTrue(paragraph(0) in out)
    }

    @Test fun an_api_model_still_gets_a_long_transcript_whole() = runBlocking {
        val transcript = longTranscript(needleAt = 350)
        val request = ask(ComputeMode.Api, transcript, "What was the zebrafund budget?")
        assertEquals(transcript, transcriptSection(request))
    }

    @Test fun chat_history_keeps_only_the_newest_turns_that_fit() = runBlocking {
        val history = (0 until 50).map { AskAiChat.Turn(if (it % 2 == 0) "user" else "assistant", "turn $it " + "x".repeat(180)) }
        val request = ask(ComputeMode.Local, paragraph(1), "And then?", history)
        val prior = request.userPrompt.substringAfter("Prior turns:\n").substringBefore("\n\nAnd then?")
        assertTrue(prior.length <= AskContextBudget.LOCAL.historyChars, "history ${prior.length} chars")
        assertTrue("turn 49 " in prior, "the newest turn is kept")
        assertFalse("turn 0 " in prior, "the oldest turn is dropped")
        assertTrue(prior.indexOf("turn 48 ") < prior.indexOf("turn 49 "), "oldest first")
    }
}
