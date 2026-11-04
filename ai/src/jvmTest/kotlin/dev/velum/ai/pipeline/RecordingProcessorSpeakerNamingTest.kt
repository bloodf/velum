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

/** #320: with the voice key unavailable, a first run keeps going with bare labels; a rerun must not wipe typed names. */
class RecordingProcessorSpeakerNamingTest {
    private var transcribeCalls = 0
    private var namingReady = true

    private suspend fun process(
        repo: VelumRepository,
        namesUnavailable: Boolean,
    ): JobProgress {
        val settings = SettingsRepository(InMemorySecureStore()).also {
            it.load()
            it.setApiKey("sk-test-not-real")
            it.setTranscriptionMode(ComputeMode.Api)
            it.setDiarizationMode(ComputeMode.Local)
            it.setSummaryMode(ComputeMode.Local)
        }
        val asr = object : Transcriber {
            override val engineId = "synthetic-asr"
            override suspend fun transcribe(input: AudioInput, modelId: String): TranscriptionResult {
                transcribeCalls++
                return TranscriptionResult(text = "Synthetic new words", engineId = engineId, modelId = modelId)
            }
        }
        val diar = object : Diarizer {
            override val engineId = "synthetic-diar"
            override suspend fun speakerNamingReady() = namingReady
            override suspend fun diarize(input: AudioInput, modelId: String) = DiarizationResult(
                spans = listOf(SpeakerSpan("S1", 0, 1_000)),
                engineId = engineId,
                modelId = modelId,
                speakerNamesUnavailable = namesUnavailable,
            )
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
        return RecordingProcessor(router, repo, settings).process("rec", "velum.meeting", retranscribe = true).last()
    }

    private suspend fun recording(named: Boolean): VelumRepository {
        val repo = VelumRepository(createDatabase(DriverFactory()))
        val wav = tinyWav()
        val path = File.createTempFile("velum-naming", ".wav").apply { writeBytes(wav); deleteOnExit() }.absolutePath
        repo.insertRecording(
            id = "rec", sessionId = null, folderId = null, title = "t", source = "phone", deviceSerial = null,
            startedAt = 1, durationSeconds = 1, sizeBytes = wav.size.toLong(), localPath = path,
            transcript = "Typed earlier", summaryMarkdown = null, status = "queued",
        )
        if (named) {
            repo.updateSpeakers("rec", listOf(VelumRepository.SpeakerUpsert("rec-S1", "S1", "Synthetic Alice", 60)))
        }
        return repo
    }

    @Test fun first_run_without_the_key_goes_on_with_bare_labels() = runBlocking<Unit> {
        val repo = recording(named = false)
        assertIs<JobProgress.Succeeded>(process(repo, namesUnavailable = true))
        assertEquals(listOf("S1"), repo.getSpeakers("rec").map { it.label })
        assertTrue(repo.getRecording("rec")!!.transcript!!.contains("Synthetic new words"))
    }

    @Test fun a_rerun_without_the_key_fails_and_keeps_typed_names_and_the_transcript() = runBlocking<Unit> {
        val repo = recording(named = true)
        assertIs<JobProgress.Failed>(process(repo, namesUnavailable = true))
        assertEquals(listOf("Synthetic Alice"), repo.getSpeakers("rec").map { it.display_name })
        assertEquals("Typed earlier", repo.getRecording("rec")!!.transcript)
    }

    @Test fun a_rerun_with_named_speakers_and_no_key_fails_before_the_transcription_is_paid_for() = runBlocking<Unit> {
        val repo = recording(named = true)
        namingReady = false
        val result = process(repo, namesUnavailable = true)
        assertIs<JobProgress.Failed>(result)
        assertEquals(0, transcribeCalls, "the transcription ran before the key was checked")
        assertEquals(SPEAKER_NAMES_KEPT_MESSAGE, result.message)
        assertEquals("Typed earlier", repo.getRecording("rec")!!.transcript)
    }

    @Test fun a_first_run_does_not_wait_for_the_key_probe() = runBlocking<Unit> {
        val repo = recording(named = false)
        namingReady = false
        assertIs<JobProgress.Succeeded>(process(repo, namesUnavailable = true))
        assertEquals(1, transcribeCalls)
    }

    @Test fun a_rerun_with_the_key_replaces_the_speakers_as_before() = runBlocking<Unit> {
        val repo = recording(named = true)
        assertIs<JobProgress.Succeeded>(process(repo, namesUnavailable = false))
        assertTrue(repo.getRecording("rec")!!.transcript!!.contains("Synthetic new words"))
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
