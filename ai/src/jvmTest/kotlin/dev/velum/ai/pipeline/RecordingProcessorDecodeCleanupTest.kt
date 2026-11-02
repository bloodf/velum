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
import dev.velum.ai.local.ModelStoreDir
import dev.velum.ai.local.defaultModelStoreRoot
import dev.velum.core.data.DriverFactory
import dev.velum.core.data.VelumRepository
import dev.velum.core.data.createDatabase
import dev.velum.core.model.ComputeMode
import dev.velum.core.security.InMemorySecureStore
import dev.velum.core.settings.ApiEndpointProfile
import dev.velum.core.settings.ApiModelOverride
import dev.velum.core.settings.SettingsRepository
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * #86 decode stage from disk (synthetic audio only): WAV PCM copied in slices matches the source,
 * and decode failure, cancellation and a full disk leave no temp PCM and keep the recording file.
 */
class RecordingProcessorDecodeCleanupTest {
    private class Harness(val transcribe: suspend (AudioInput) -> Unit, readFileBytes: ((String) -> ByteArray)? = null) {
        val repo = VelumRepository(createDatabase(DriverFactory()))
        val settings = SettingsRepository(InMemorySecureStore()).also {
            runBlocking {
                it.load()
                it.upsertProfile(ApiEndpointProfile(id = "work", name = "Work", baseUrl = "https://example.invalid/v1"))
                it.setProfileApiKey("work", "sk-test-work")
                it.setTranscriptionMode(ComputeMode.Api)
                it.setDiarizationMode(ComputeMode.Local)
                it.setSummaryMode(ComputeMode.Local)
            }
        }
        private val asr = object : Transcriber {
            override val engineId = "asr"
            override suspend fun transcribe(input: AudioInput, modelId: String): TranscriptionResult {
                this@Harness.transcribe(input)
                return TranscriptionResult(text = "hello", engineId = engineId, modelId = modelId)
            }
        }
        private val diar = object : Diarizer {
            override val engineId = "noop"
            override suspend fun diarize(input: AudioInput, modelId: String) =
                DiarizationResult(spans = emptyList(), engineId = engineId, modelId = modelId)
        }
        private val llm = object : LlmEngine {
            override val engineId = "noop"
            override suspend fun complete(request: LlmRequest, modelId: String) =
                LlmResult(text = "## Summary\nok", engineId = engineId, modelId = modelId)
        }
        val processor = RecordingProcessor(
            AiTaskRouter(
                settings = MutableStateFlow(settings.settings.value),
                localTranscriber = asr, apiTranscriber = asr,
                localDiarizer = diar, apiDiarizer = diar,
                localLlm = llm, apiLlm = llm,
            ),
            repo,
            settings,
            readFileBytes = readFileBytes ?: { path -> ModelStoreDir(path).readBytes() },
        )

        suspend fun insert(id: String, file: File) = repo.insertRecording(
            id = id, sessionId = null, folderId = null, title = "T", source = "note_pro",
            deviceSerial = null, startedAt = 1, durationSeconds = 1, sizeBytes = file.length(),
            localPath = file.path, transcript = null, summaryMarkdown = null, status = "queued",
        )
    }

    private val API = ApiModelOverride(profileId = "work", modelId = "whisper-custom")

    private fun spillDir(id: String) =
        File(ModelStoreDir(defaultModelStoreRoot().absolutePath).resolve("tmp").resolve("pcm-$id").absolutePath)

    private fun tempAudio(suffix: String, bytes: ByteArray) =
        File.createTempFile("velum-86-decode", suffix).apply { writeBytes(bytes); deleteOnExit() }

    /** 16 kHz mono WAV with a LIST chunk (odd size, padded) ahead of `data`, so the header walk is real. */
    private fun wav(pcm: ByteArray, rate: Int = 16_000): ByteArray {
        val list = "LIST".toByteArray() + le32(5) + "INFOx".toByteArray() + byteArrayOf(0)
        val fmt = "fmt ".toByteArray() + le32(16) + le16(1) + le16(1) + le32(rate) + le32(rate * 2) + le16(2) + le16(16)
        val data = "data".toByteArray() + le32(pcm.size) + pcm
        val body = "WAVE".toByteArray() + fmt + list + data
        return "RIFF".toByteArray() + le32(body.size) + body
    }

    private fun le32(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
    private fun le16(v: Int) = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()

    @Test
    fun wav_from_disk_is_copied_in_slices_and_matches_expected_pcm() = runBlocking {
        // 20 s mono s16le = 640,000 bytes: three 256 KiB copy slices, one ASR window.
        val pcm = ByteBuffer.allocate(20 * 16_000 * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            for (i in 0 until 20 * 16_000) putShort((sin(2 * PI * 440 * i / 16_000) * 9000).toInt().toShort())
        }.array()
        val captured = mutableListOf<AudioInput>()
        val h = Harness({ captured += it })
        val src = tempAudio(".wav", wav(pcm))
        h.insert("rec-86-wav", src)
        val events = h.processor.process("rec-86-wav", templateId = "velum.meeting", apiModelOverride = API).toList()
        assertTrue(events.last() is JobProgress.Succeeded, "last=${events.last()}")
        assertEquals(1, captured.size)
        assertEquals(16_000, captured[0].sampleRateHz)
        assertContentEquals(pcm, captured[0].bytes)
        assertFalse(spillDir("rec-86-wav").exists(), "temp PCM removed after success")
    }

    @Test
    fun corrupt_ogg_fails_explicitly_cleans_temp_pcm_and_keeps_source() = runBlocking {
        val bytes = "OggS".toByteArray() + ByteArray(40) + "XggS".toByteArray() + ByteArray(40)
        val h = Harness({ error("ASR must not run on undecodable audio") })
        val src = tempAudio(".ogg", bytes)
        h.insert("rec-86-corrupt", src)
        val events = h.processor.process("rec-86-corrupt", templateId = "velum.meeting", apiModelOverride = API).toList()
        val failed = events.last() as JobProgress.Failed
        assertTrue(failed.message.isNotBlank())
        assertEquals("failed", h.repo.getRecording("rec-86-corrupt")!!.status)
        assertFalse(spillDir("rec-86-corrupt").exists())
        assertContentEquals(bytes, src.readBytes(), "source recording preserved")
    }

    @Test
    fun cancellation_mid_processing_cleans_temp_pcm_and_keeps_source() = runBlocking {
        val bytes = wav(ByteArray(64_000))
        val entered = CompletableDeferred<Unit>()
        val h = Harness({ entered.complete(Unit); awaitCancellation() })
        val src = tempAudio(".wav", bytes)
        h.insert("rec-86-cancel", src)
        val job = launch(Dispatchers.Default) { h.processor.process("rec-86-cancel", templateId = "velum.meeting", apiModelOverride = API).toList() }
        entered.await()
        assertTrue(File(spillDir("rec-86-cancel"), "pcm.s16le").isFile, "decode wrote temp PCM before cancel")
        job.cancelAndJoin()
        assertFalse(spillDir("rec-86-cancel").exists(), "temp PCM removed on cancellation")
        assertContentEquals(bytes, src.readBytes())
        assertEquals("failed", h.repo.getRecording("rec-86-cancel")!!.status)
    }

    @Test
    fun full_disk_reports_insufficient_storage_and_keeps_source() = runBlocking {
        val bytes = ByteArray(1024) { 1 }
        val h = Harness(
            { error("ASR must not run") },
            readFileBytes = { throw IOException("write failed: ENOSPC (No space left on device)") },
        )
        val src = tempAudio(".bin", bytes)
        h.insert("rec-86-enospc", src)
        val events = h.processor.process("rec-86-enospc", templateId = "velum.meeting", apiModelOverride = API).toList()
        assertEquals(INSUFFICIENT_STORAGE_MESSAGE, (events.last() as JobProgress.Failed).message)
        assertEquals(INSUFFICIENT_STORAGE_MESSAGE, h.repo.getProcessingError("rec-86-enospc"))
        assertFalse(spillDir("rec-86-enospc").exists())
        assertContentEquals(bytes, src.readBytes())
    }

    @Test
    fun real_enospc_from_dev_full_is_detected() {
        val devFull = File("/dev/full")
        if (!devFull.exists()) return // Linux-only: macOS has no /dev/full
        val e = kotlin.runCatching { ModelStoreDir(devFull.path).appendBytes(ByteArray(4096)) }.exceptionOrNull()
        assertTrue(e != null && isOutOfStorage(e), "real write failure: $e")
    }

    @Test
    fun out_of_storage_detection_walks_causes_only() {
        assertTrue(isOutOfStorage(RuntimeException("wrap", IOException("No space left on device"))))
        assertFalse(isOutOfStorage(IOException("Permission denied")))
    }
}
