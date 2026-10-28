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
import dev.velum.core.settings.ApiEndpointProfile
import dev.velum.core.settings.ApiModelOverride
import dev.velum.core.settings.SettingsRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking

class RecordingProcessorAsrOverrideTest {
    @Test
    fun process_passesApiOverrideIntoAsrAudioInput() = runBlocking {
        val repo = VelumRepository(createDatabase(DriverFactory()))
        val store = InMemorySecureStore()
        val settings = SettingsRepository(store).also {
            it.load()
            it.upsertProfile(
                ApiEndpointProfile(
                    id = "work",
                    name = "Work",
                    baseUrl = "https://example.invalid/v1",
                ),
            )
            it.setProfileApiKey("work", "sk-test-work")
            it.setTranscriptionMode(ComputeMode.Api)
            it.setDiarizationMode(ComputeMode.Local)
            it.setSummaryMode(ComputeMode.Local)
        }
        var capturedOverride: ApiModelOverride? = null
        val asr = object : Transcriber {
            override val engineId = "capture-asr"
            override suspend fun transcribe(input: AudioInput, modelId: String): TranscriptionResult {
                capturedOverride = input.apiOverride
                return TranscriptionResult(text = "hello", engineId = engineId, modelId = modelId)
            }
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
            localTranscriber = asr,
            apiTranscriber = asr,
            localDiarizer = diar,
            apiDiarizer = diar,
            localLlm = llm,
            apiLlm = llm,
        )
        val wav = tinyWav()
        val path = java.io.File.createTempFile("velum-asr-ov", ".wav").apply {
            writeBytes(wav)
            deleteOnExit()
        }.absolutePath
        repo.insertRecording(
            id = "rec-asr-ov",
            sessionId = null,
            folderId = null,
            title = "Override",
            source = "note_pro",
            deviceSerial = null,
            startedAt = 1,
            durationSeconds = 1,
            sizeBytes = wav.size.toLong(),
            localPath = path,
            transcript = null,
            summaryMarkdown = null,
            status = "queued",
        )
        val processor = RecordingProcessor(router, repo, settings)
        val override = ApiModelOverride(profileId = "work", modelId = "whisper-custom")
        val last = processor.process(
            recordingId = "rec-asr-ov",
            templateId = "velum.meeting",
            retranscribe = true,
            apiModelOverride = override,
        ).last()
        assertIs<JobProgress.Succeeded>(last)
        assertNotNull(capturedOverride)
        assertEquals("work", capturedOverride?.profileId)
        assertEquals("whisper-custom", capturedOverride?.modelId)
    }

    private fun tinyWav(): ByteArray {
        val dataSize = 32
        val out = ByteArray(44 + dataSize)
        "RIFF".toByteArray().copyInto(out, 0)
        writeInt(out, 4, 36 + dataSize)
        "WAVE".toByteArray().copyInto(out, 8)
        "fmt ".toByteArray().copyInto(out, 12)
        writeInt(out, 16, 16)
        out[20] = 1
        out[22] = 1
        writeInt(out, 24, 16_000)
        writeInt(out, 28, 32_000)
        out[32] = 2
        out[34] = 16
        "data".toByteArray().copyInto(out, 36)
        writeInt(out, 40, dataSize)
        return out
    }

    private fun writeInt(bytes: ByteArray, at: Int, value: Int) {
        bytes[at] = (value and 0xFF).toByte()
        bytes[at + 1] = ((value ushr 8) and 0xFF).toByte()
        bytes[at + 2] = ((value ushr 16) and 0xFF).toByte()
        bytes[at + 3] = ((value ushr 24) and 0xFF).toByte()
    }
}
