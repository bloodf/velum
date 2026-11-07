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

class RetranscribeKeepsMarkersTest {
    @Test
    fun retranscribing_replaces_the_text_but_keeps_markers_out_of_the_summary() = runBlocking<Unit> {
        val repo = VelumRepository(createDatabase(DriverFactory()))
        val settings = SettingsRepository(InMemorySecureStore()).also {
            it.load()
            it.setApiKey("sk-test-not-real")
            it.setTranscriptionMode(ComputeMode.Api)
            it.setDiarizationMode(ComputeMode.Local)
            it.setSummaryMode(ComputeMode.Local)
        }
        val asr = object : Transcriber {
            override val engineId = "synthetic-asr"
            override suspend fun transcribe(input: AudioInput, modelId: String) =
                TranscriptionResult(text = "Synthetic new words", engineId = engineId, modelId = modelId)
        }
        val diar = object : Diarizer {
            override val engineId = "noop"
            override suspend fun diarize(input: AudioInput, modelId: String) =
                DiarizationResult(spans = emptyList(), engineId = engineId, modelId = modelId)
        }
        val prompts = mutableListOf<String>()
        val llm = object : LlmEngine {
            override val engineId = "noop"
            override suspend fun complete(request: LlmRequest, modelId: String): LlmResult {
                prompts += request.userPrompt
                return LlmResult(text = "## Summary\nok", engineId = engineId, modelId = modelId)
            }
        }
        val router = AiTaskRouter(
            settings = MutableStateFlow(settings.settings.value),
            localTranscriber = asr, apiTranscriber = asr,
            localDiarizer = diar, apiDiarizer = diar,
            localLlm = llm, apiLlm = llm,
        )
        val wav = tinyWav()
        val path = File.createTempFile("velum-markers", ".wav").apply { writeBytes(wav); deleteOnExit() }.absolutePath
        repo.insertRecording(
            id = "rec", sessionId = null, folderId = null, title = "t", source = "phone", deviceSerial = null,
            startedAt = 1, durationSeconds = 1, sizeBytes = wav.size.toLong(), localPath = path,
            transcript = "## Markers\n- **1:05** Synthetic note\n", summaryMarkdown = null, status = "queued",
        )

        assertIs<JobProgress.Succeeded>(RecordingProcessor(router, repo, settings).process("rec", "velum.meeting", retranscribe = true).last())

        val saved = repo.getRecording("rec")!!.transcript!!
        assertTrue(saved.contains("Synthetic new words"), saved)
        assertTrue(saved.endsWith("## Markers\n- **1:05** Synthetic note"), saved)
        assertTrue(prompts.isNotEmpty() && prompts.none { it.contains("Synthetic note") })
    }

    private fun tinyWav(): ByteArray {
        val dataSize = 32
        return ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + dataSize); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(16_000); putInt(32_000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(dataSize)
        }.array()
    }
}
