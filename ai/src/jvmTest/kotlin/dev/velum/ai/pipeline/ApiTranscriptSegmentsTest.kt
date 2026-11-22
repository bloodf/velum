package dev.velum.ai.pipeline

import dev.velum.ai.engine.AiTaskRouter
import dev.velum.ai.engine.AudioInput
import dev.velum.ai.engine.DiarizationResult
import dev.velum.ai.engine.Diarizer
import dev.velum.ai.engine.JobProgress
import dev.velum.ai.engine.LlmEngine
import dev.velum.ai.engine.LlmRequest
import dev.velum.ai.engine.LlmResult
import dev.velum.ai.engine.SpeakerSpan
import dev.velum.ai.engine.Transcriber
import dev.velum.ai.engine.TranscriptionResult
import dev.velum.core.data.DriverFactory
import dev.velum.core.data.VelumRepository
import dev.velum.core.data.createDatabase
import dev.velum.core.model.ComputeMode
import dev.velum.core.security.InMemorySecureStore
import dev.velum.core.settings.SettingsRepository
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking

/**
 * API transcription returns text only. Each window's text now gets a window-long segment, so local diarization labels
 * the transcript and its lines carry timestamps, as with the local engines.
 */
class ApiTranscriptSegmentsTest {
    private suspend fun run(seconds: Int, asrText: (AudioInput) -> String, spans: List<SpeakerSpan>): String {
        val repo = VelumRepository(createDatabase(DriverFactory()))
        val settings = SettingsRepository(InMemorySecureStore()).also {
            it.load()
            it.setApiKey("sk-test-not-real")
            it.setTranscriptionMode(ComputeMode.Api)
            it.setDiarizationMode(ComputeMode.Local)
            it.setSummaryMode(ComputeMode.Local)
        }
        val asr = object : Transcriber {
            override val engineId = "synthetic-api-asr"
            override suspend fun transcribe(input: AudioInput, modelId: String) =
                TranscriptionResult(text = asrText(input), engineId = engineId, modelId = modelId)
        }
        val diar = object : Diarizer {
            override val engineId = "synthetic-diar"
            override suspend fun diarize(input: AudioInput, modelId: String) =
                DiarizationResult(spans = spans, engineId = engineId, modelId = modelId)
        }
        val llm = object : LlmEngine {
            override val engineId = "noop"
            override suspend fun complete(request: LlmRequest, modelId: String) =
                LlmResult(text = "## Summary\nok", engineId = engineId, modelId = modelId)
        }
        val router = AiTaskRouter(MutableStateFlow(settings.settings.value), asr, asr, diar, diar, llm, llm)
        val wav = wav(seconds)
        val path = File.createTempFile("velum-api-segments", ".wav").apply { writeBytes(wav); deleteOnExit() }.absolutePath
        repo.insertRecording(
            id = "rec", sessionId = null, folderId = null, title = "t", source = "phone", deviceSerial = null,
            startedAt = 1, durationSeconds = seconds.toLong(), sizeBytes = wav.size.toLong(), localPath = path,
            transcript = null, summaryMarkdown = null, status = "queued",
        )
        assertIs<JobProgress.Succeeded>(RecordingProcessor(router, repo, settings).process("rec", "velum.meeting").last())
        return repo.getRecording("rec")!!.transcript.orEmpty()
    }

    @Test fun local_diarization_labels_an_api_transcript() = runBlocking {
        val transcript = run(2, { "Good morning everyone" }, listOf(SpeakerSpan("S1", 0, 2_000)))
        assertEquals("**Speaker 1** · 0:00\nGood morning everyone", transcript)
    }

    @Test fun each_api_window_gets_its_own_timestamped_speaker() = runBlocking {
        var window = 0
        val transcript = run(
            58,
            { if (window++ == 0) "first half of the meeting" else "second half of the meeting" },
            listOf(SpeakerSpan("S1", 0, 29_000), SpeakerSpan("S2", 29_000, 58_000)),
        )
        assertTrue("**Speaker 1** · 0:00\nfirst half of the meeting" in transcript, transcript)
        assertTrue("**Speaker 2** · 0:28\nsecond half of the meeting" in transcript, transcript)
    }

    @Test fun speaker_lines_written_by_the_provider_are_kept() = runBlocking {
        val transcript = run(2, { "Speaker 1: Hi.\nSpeaker 2: Hello." }, listOf(SpeakerSpan("S1", 0, 2_000)))
        assertTrue(transcript.contains("**Speaker 1:** Hi.") && transcript.contains("**Speaker 2:** Hello."), transcript)
    }

    private fun wav(seconds: Int): ByteArray {
        val dataSize = seconds * 32_000
        return ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + dataSize); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(16_000); putInt(32_000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(dataSize)
        }.array()
    }
}
