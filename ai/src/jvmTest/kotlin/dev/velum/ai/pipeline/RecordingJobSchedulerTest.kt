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
import dev.velum.core.settings.ApiModelOverride
import dev.velum.core.settings.SettingsRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class RecordingJobSchedulerTest {
    @Test
    fun recordingWorkHasAutoFlowParams_detectsTemplateOrOverride() {
        assertFalse(recordingWorkHasAutoFlowParams(null, null))
        assertFalse(recordingWorkHasAutoFlowParams("", ApiModelOverride()))
        assertTrue(recordingWorkHasAutoFlowParams("velum.meeting", null))
        assertTrue(
            recordingWorkHasAutoFlowParams(null, ApiModelOverride(modelId = "whisper-1")),
        )
        assertTrue(
            recordingWorkHasAutoFlowParams(null, ApiModelOverride(profileId = "work")),
        )
    }

    @Test
    fun coroutineScheduler_dedupesPerRecordingId() = runBlocking {
        val repo = VelumRepository(createDatabase(DriverFactory()))
        val settings = SettingsRepository(InMemorySecureStore()).also {
            it.load()
            it.setTranscriptionMode(ComputeMode.Local)
            it.setDiarizationMode(ComputeMode.Local)
            it.setSummaryMode(ComputeMode.Local)
        }
        var active = 0
        var maxActive = 0
        var launches = 0
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val asr = object : Transcriber {
            override val engineId = "slow-asr"
            override suspend fun transcribe(input: AudioInput, modelId: String): TranscriptionResult {
                launches++
                active++
                maxActive = maxOf(maxActive, active)
                try {
                    gate.await()
                } finally {
                    active--
                }
                return TranscriptionResult(text = "hi", engineId = engineId, modelId = modelId)
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
                LlmResult(text = "## ok", engineId = engineId, modelId = modelId)
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
        val path = java.io.File.createTempFile("velum-sched", ".wav").apply {
            writeBytes(wav)
            deleteOnExit()
        }.absolutePath
        repo.insertRecording(
            id = "rec-dedupe",
            sessionId = null,
            folderId = null,
            title = "Dedupe",
            source = "phone",
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
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val scheduler = createCoroutineRecordingJobScheduler(processor, scope)
        scheduler.enqueue("rec-dedupe", null, true, null)
        delay(80)
        scheduler.enqueue(
            "rec-dedupe",
            "velum.meeting",
            true,
            ApiModelOverride(modelId = "m"),
        )
        withTimeout(5_000) { while (launches < 2) delay(20) }
        assertEquals(1, active, "prior job cancelled; only replacement active")
        assertEquals(1, maxActive, "must never run two jobs for same id")
        gate.complete(Unit)
        withTimeout(10_000) {
            while (repo.getRecording("rec-dedupe")?.status != "ready") delay(50)
        }
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
