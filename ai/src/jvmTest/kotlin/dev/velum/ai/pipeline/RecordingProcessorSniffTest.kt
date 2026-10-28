package dev.velum.ai.pipeline

import dev.velum.ai.audio.OggOpusDemuxer
import dev.velum.ai.audio.PcmWav
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
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking

/**
 * Extension-less imports must sniff ≤256 KiB and route to streaming decode — never load the
 * whole file — and unknown formats must fail clearly.
 */
class RecordingProcessorSniffTest {
    @Test
    fun extensionLessWav_sniffsAndStreams() {
        runBlocking {
            val pcm = ByteArray(8_192) { (it % 127).toByte() }
            val wav = PcmWav.encode(pcm, sampleRateHz = 16_000)
            val path = writeTempNoExt(wav, prefix = "velum-sniff-wav")
            val last = processFile(path)
            assertIs<JobProgress.Succeeded>(last)
        }
    }

    @Test
    fun extensionLessOgg_sniffsAndStreams() {
        runBlocking {
            val ogg = minimalOggOpusHead()
            val path = writeTempNoExt(ogg, prefix = "velum-sniff-ogg")
            // Header-only Ogg demuxes to empty PCM; seed a transcript so processing can finish
            // after the streaming Opus path (proves sniff routed to decodePathToPcmFile).
            val last = processFile(path, existingTranscript = "seed")
            assertIs<JobProgress.Succeeded>(last)
        }
    }

    @Test
    fun extensionLessUnknown_failsWithoutLoadingAsPcm() {
        runBlocking {
            val junk = ByteArray(64 * 1024) { 0x7F }
            val path = writeTempNoExt(junk, prefix = "velum-sniff-unk")
            val last = processFile(path)
            assertIs<JobProgress.Failed>(last)
            assertTrue(
                last.message.contains("Unsupported audio format", ignoreCase = true),
                "expected clear unsupported-format error, got: ${last.message}",
            )
        }
    }

    @Test
    fun contentSniffCap_is256KiB() {
        assertTrue(RecordingProcessor.CONTENT_SNIFF_MAX_BYTES == 256 * 1024)
        assertTrue(RecordingProcessor.STREAM_COPY_CHUNK_BYTES == 256 * 1024)
    }

    private suspend fun processFile(
        path: String,
        existingTranscript: String? = null,
    ): JobProgress {
        val repo = VelumRepository(createDatabase(DriverFactory()))
        val settings = SettingsRepository(InMemorySecureStore()).also {
            it.load()
            it.setTranscriptionMode(ComputeMode.Local)
            it.setDiarizationMode(ComputeMode.Local)
            it.setSummaryMode(ComputeMode.Local)
        }
        val asr = object : Transcriber {
            override val engineId = "fake-asr"
            override suspend fun transcribe(input: AudioInput, modelId: String) =
                TranscriptionResult(text = "ok", engineId = engineId, modelId = modelId)
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
        val id = "rec-sniff-${path.hashCode()}"
        repo.insertRecording(
            id = id,
            sessionId = null,
            folderId = null,
            title = "Sniff",
            source = "import",
            deviceSerial = null,
            startedAt = 1,
            durationSeconds = 1,
            sizeBytes = File(path).length(),
            localPath = path,
            transcript = existingTranscript,
            summaryMarkdown = null,
            status = "queued",
        )
        return RecordingProcessor(router, repo, settings)
            .process(recordingId = id, templateId = "velum.meeting", retranscribe = true)
            .last()
    }

    private fun writeTempNoExt(bytes: ByteArray, prefix: String): String {
        val file = File.createTempFile(prefix, null).apply {
            writeBytes(bytes)
            deleteOnExit()
        }
        // createTempFile always adds a suffix; rename to a truly extension-less path.
        val noExt = File(file.parentFile, "$prefix-${System.nanoTime()}")
        check(file.renameTo(noExt)) { "rename failed" }
        noExt.deleteOnExit()
        return noExt.absolutePath
    }

    private fun minimalOggOpusHead(): ByteArray {
        val head = byteArrayOf(
            'O'.code.toByte(), 'p'.code.toByte(), 'u'.code.toByte(), 's'.code.toByte(),
            'H'.code.toByte(), 'e'.code.toByte(), 'a'.code.toByte(), 'd'.code.toByte(),
            1, 1, 0, 0,
            0x80.toByte(), 0xBB.toByte(), 0x00, 0x00,
            0, 0, 0,
        )
        // Sanity: demuxer accepts this standalone page.
        val meta = OggOpusDemuxer.demuxForEach(buildOggPage(head)) { }
        assertTrue(meta.sampleRateHz == 48_000)
        return buildOggPage(head)
    }

    private fun buildOggPage(packet: ByteArray): ByteArray {
        val header = ByteArray(27 + 1 + packet.size)
        header[0] = 'O'.code.toByte()
        header[1] = 'g'.code.toByte()
        header[2] = 'g'.code.toByte()
        header[3] = 'S'.code.toByte()
        header[26] = 1
        header[27] = packet.size.toByte()
        packet.copyInto(header, 28)
        return header
    }
}
