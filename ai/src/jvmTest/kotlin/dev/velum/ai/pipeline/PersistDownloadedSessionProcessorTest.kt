package dev.velum.ai.pipeline

import dev.velum.ai.audio.DownloadedSessionAudioPersist
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
import java.io.RandomAccessFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking

/**
 * Simulates [dev.velum.app.di.AppContainer.persistDownloadedSession]: prepare via
 * [DownloadedSessionAudioPersist] (same helper AppContainer uses), write the file, insert the
 * recording, then run [RecordingProcessor] — covering every decrypted-body shape that helper emits.
 */
class PersistDownloadedSessionProcessorTest {
    @Test
    fun persist_oggOpusSession_streamsThroughProcessor() {
        runBlocking {
            val ogg = minimalOggOpusHead()
            val prepared = DownloadedSessionAudioPersist.prepare(ogg)
            assertEquals("ogg", prepared.extension)
            val last = persistAndProcess(
                prepared = prepared,
                recordingId = "persist-ogg",
                existingTranscript = "seed", // header-only Ogg → empty PCM
            )
            assertIs<JobProgress.Succeeded>(last)
        }
    }

    @Test
    fun persist_wavSession_streamsThroughProcessor() {
        runBlocking {
            val pcm = ByteArray(4_096) { (it % 63).toByte() }
            val wav = PcmWav.encode(pcm, sampleRateHz = 16_000)
            val prepared = DownloadedSessionAudioPersist.prepare(wav)
            assertEquals("wav", prepared.extension)
            val last = persistAndProcess(prepared = prepared, recordingId = "persist-wav")
            assertIs<JobProgress.Succeeded>(last)
        }
    }

    @Test
    fun persist_rawPcmSession_wrapsWavAndStreamsWithBoundedReads() {
        runBlocking {
            // Large enough that STREAM_COPY_CHUNK_BYTES < file size / 4.
            val pcm = ByteArray(1_500_000) { (it % 127).toByte() }
            val prepared = DownloadedSessionAudioPersist.prepare(
                pcm,
                sampleRateHz = DownloadedSessionAudioPersist.DEFAULT_SAMPLE_RATE_HZ,
                channels = DownloadedSessionAudioPersist.DEFAULT_CHANNELS,
            )
            assertEquals("wav", prepared.extension)
            assertTrue(PcmWav.looksLikeWav(prepared.bytes))
            assertTrue(prepared.bytes.size > RecordingProcessor.STREAM_COPY_CHUNK_BYTES * 4)

            val dir = File.createTempFile("velum-persist-raw", null).apply {
                delete()
                mkdirs()
            }
            try {
                val path = File(dir, "session-raw.wav")
                path.writeBytes(prepared.bytes)
                // Bound check: streaming WAV copy never reads more than the chunk size.
                var maxRead = 0
                RecordingProcessor.streamCopyRange(
                    start = 44L,
                    length = pcm.size.toLong(),
                    chunkBytes = RecordingProcessor.STREAM_COPY_CHUNK_BYTES,
                    readAt = { offset, n ->
                        maxRead = maxOf(maxRead, n)
                        RandomAccessFile(path, "r").use { raf ->
                            raf.seek(offset)
                            val buf = ByteArray(n)
                            raf.readFully(buf)
                            buf
                        }
                    },
                    writeAll = { },
                    append = { chunk ->
                        assertTrue(chunk.size <= RecordingProcessor.STREAM_COPY_CHUNK_BYTES)
                    },
                )
                assertTrue(
                    maxRead <= RecordingProcessor.STREAM_COPY_CHUNK_BYTES,
                    "maxRead=$maxRead",
                )
                assertTrue(maxRead < prepared.bytes.size / 4)

                val last = persistAndProcess(
                    prepared = prepared,
                    recordingId = "persist-raw-pcm",
                    destDir = dir,
                )
                assertIs<JobProgress.Succeeded>(last)
            } finally {
                dir.deleteRecursively()
            }
        }
    }

    @Test
    fun persist_neverEmitsBinExtension() {
        val cases = listOf(
            byteArrayOf('O'.code.toByte(), 'g'.code.toByte(), 'g'.code.toByte(), 'S'.code.toByte()),
            PcmWav.encode(ByteArray(16), 16_000),
            ByteArray(256) { 0x55 },
        )
        for (bytes in cases) {
            val prepared = DownloadedSessionAudioPersist.prepare(bytes)
            assertTrue(
                prepared.extension == "ogg" || prepared.extension == "wav",
                "got ${prepared.extension}",
            )
        }
    }

    private suspend fun persistAndProcess(
        prepared: DownloadedSessionAudioPersist.Prepared,
        recordingId: String,
        existingTranscript: String? = null,
        destDir: File? = null,
    ): JobProgress {
        val dir = destDir ?: File.createTempFile("velum-persist-$recordingId", null).apply {
            delete()
            mkdirs()
        }
        val path = File(dir, "$recordingId.${prepared.extension}").apply {
            writeBytes(prepared.bytes)
            deleteOnExit()
        }
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
        repo.insertRecording(
            id = recordingId,
            sessionId = 42L,
            folderId = null,
            title = "Downloaded",
            source = "note_pro",
            deviceSerial = "881E2E01",
            startedAt = 1_700_000_000L,
            durationSeconds = 1,
            sizeBytes = prepared.bytes.size.toLong(),
            localPath = path.absolutePath,
            transcript = existingTranscript,
            summaryMarkdown = null,
            status = "queued",
        )
        return RecordingProcessor(router, repo, settings)
            .process(recordingId = recordingId, templateId = "velum.meeting", retranscribe = true)
            .last()
    }

    private fun minimalOggOpusHead(): ByteArray {
        val head = byteArrayOf(
            'O'.code.toByte(), 'p'.code.toByte(), 'u'.code.toByte(), 's'.code.toByte(),
            'H'.code.toByte(), 'e'.code.toByte(), 'a'.code.toByte(), 'd'.code.toByte(),
            1, 1, 0, 0,
            0x80.toByte(), 0xBB.toByte(), 0x00, 0x00,
            0, 0, 0,
        )
        val page = ByteArray(27 + 1 + head.size)
        page[0] = 'O'.code.toByte()
        page[1] = 'g'.code.toByte()
        page[2] = 'g'.code.toByte()
        page[3] = 'S'.code.toByte()
        page[26] = 1
        page[27] = head.size.toByte()
        head.copyInto(page, 28)
        val meta = OggOpusDemuxer.demuxForEach(page) { }
        assertEquals(48_000, meta.sampleRateHz)
        return page
    }
}
