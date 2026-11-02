package dev.velum.ai.pipeline

import dev.velum.ai.audio.SyntheticOgg
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
import dev.velum.core.settings.ApiEndpointProfile
import dev.velum.core.settings.ApiModelOverride
import dev.velum.core.settings.SettingsRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/** A truncated Ogg tail must not be processed silently: progress carries a visible warning. */
class RecordingProcessorTruncatedAudioTest {
    private fun run(ogg: ByteArray): List<JobProgress> = runBlocking {
        val repo = VelumRepository(createDatabase(DriverFactory()))
        val settings = SettingsRepository(InMemorySecureStore()).also {
            it.load()
            it.upsertProfile(ApiEndpointProfile(id = "work", name = "Work", baseUrl = "https://example.invalid/v1"))
            it.setProfileApiKey("work", "sk-test-work")
            it.setTranscriptionMode(ComputeMode.Api)
            it.setDiarizationMode(ComputeMode.Local)
            it.setSummaryMode(ComputeMode.Local)
        }
        val asr = object : Transcriber {
            override val engineId = "asr"
            override suspend fun transcribe(input: AudioInput, modelId: String) =
                TranscriptionResult(text = "hello", engineId = engineId, modelId = modelId)
        }
        val diar = object : Diarizer {
            override val engineId = "noop"
            override suspend fun diarize(input: AudioInput, modelId: String) =
                DiarizationResult(spans = emptyList(), engineId = engineId, modelId = modelId)
        }
        val llm = object : LlmEngine {
            override val engineId = "noop"
            override suspend fun complete(request: LlmRequest, modelId: String) =
                LlmResult(text = "## Summary\nok", engineId = engineId, modelId = modelId)
        }
        val router = AiTaskRouter(
            settings = MutableStateFlow(settings.settings.value),
            localTranscriber = asr, apiTranscriber = asr,
            localDiarizer = diar, apiDiarizer = diar,
            localLlm = llm, apiLlm = llm,
        )
        val path = java.io.File.createTempFile("velum-trunc", ".ogg").apply {
            writeBytes(ogg)
            deleteOnExit()
        }.absolutePath
        repo.insertRecording(
            id = "rec-trunc", sessionId = null, folderId = null, title = "T", source = "note_pro",
            deviceSerial = null, startedAt = 1, durationSeconds = 1, sizeBytes = ogg.size.toLong(),
            localPath = path, transcript = null, summaryMarkdown = null, status = "queued",
        )
        RecordingProcessor(router, repo, settings)
            .process(
                "rec-trunc", templateId = "velum.meeting", retranscribe = true,
                apiModelOverride = ApiModelOverride(profileId = "work", modelId = "whisper-custom"),
            )
            .toList()
    }

    private val head = SyntheticOgg.page(listOf(SyntheticOgg.opusHead(1, 48_000)), 0, 2) +
        SyntheticOgg.page(listOf(SyntheticOgg.tags()), 1)
    private val audio = SyntheticOgg.page(List(25) { SyntheticOgg.audioPacket(false) }, 2)

    @Test
    fun truncated_ogg_still_processes_but_emits_warning() {
        val events = run(head + audio + ByteArray(9))
        assertTrue(events.last() is JobProgress.Succeeded, "last=${events.last()}")
        assertTrue(events.any { it is JobProgress.Running && it.message == TRUNCATED_AUDIO_MESSAGE })
    }

    @Test
    fun clean_ogg_emits_no_truncation_warning() {
        val events = run(head + audio)
        assertTrue(events.last() is JobProgress.Succeeded, "last=${events.last()}")
        assertEquals(0, events.count { it is JobProgress.Running && it.message == TRUNCATED_AUDIO_MESSAGE })
    }
}
