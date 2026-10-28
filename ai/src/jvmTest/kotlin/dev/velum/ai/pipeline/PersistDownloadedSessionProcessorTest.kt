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
import dev.velum.ai.local.ModelStoreDir
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
 * Uses [DownloadedSessionAudioPersist.persistToDirectory] (same helper AppContainer calls),
 * then runs [RecordingProcessor] for each decrypted-body shape.
 */
class PersistDownloadedSessionProcessorTest {
    @Test
    fun persist_oggOpusSession_streamsThroughProcessor() {
        runBlocking {
            val ogg = minimalOggOpusHead()
            val last = persistAndProcess(
                decrypted = ogg,
                recordingId = "persist-ogg",
                existingTranscript = "seed",
            )
            assertIs<JobProgress.Succeeded>(last)
        }
    }

    @Test
    fun persist_wavSession_streamsThroughProcessor() {
        runBlocking {
            val wav = PcmWav.encode(ByteArray(4_096) { (it % 63).toByte() }, sampleRateHz = 16_000)
            val last = persistAndProcess(decrypted = wav, recordingId = "persist-wav")
            assertIs<JobProgress.Succeeded>(last)
        }
    }

    @Test
    fun persist_rawPcmSession_wrapsWavAndStreamsWithBoundedReads() {
        runBlocking {
            val pcm = ByteArray(1_500_000) { (it % 127).toByte() }
            val dir = File.createTempFile("velum-persist-raw", null).apply {
                delete()
                mkdirs()
            }
            try {
                val written = DownloadedSessionAudioPersist.persistToDirectory(
                    decryptedBytes = pcm,
                    destDirectory = ModelStoreDir(dir.absolutePath),
                    fileBaseName = "session-raw",
                )
                assertEquals("wav", written.extension)
                assertTrue(written.sizeBytes > RecordingProcessor.STREAM_COPY_CHUNK_BYTES * 4L)

                var maxRead = 0
                RecordingProcessor.streamCopyRange(
                    start = 44L,
                    length = pcm.size.toLong(),
                    chunkBytes = RecordingProcessor.STREAM_COPY_CHUNK_BYTES,
                    readAt = { offset, n ->
                        maxRead = maxOf(maxRead, n)
                        RandomAccessFile(written.absolutePath, "r").use { raf ->
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
                assertTrue(maxRead <= RecordingProcessor.STREAM_COPY_CHUNK_BYTES)
                assertTrue(maxRead < written.sizeBytes / 4)

                val last = processExistingFile(
                    path = written.absolutePath,
                    sizeBytes = written.sizeBytes,
                    recordingId = "persist-raw-pcm",
                )
                assertIs<JobProgress.Succeeded>(last)
            } finally {
                dir.deleteRecursively()
            }
        }
    }

    @Test
    fun persist_neverEmitsBinExtension() {
        val dir = File.createTempFile("velum-persist-ext", null).apply {
            delete()
            mkdirs()
        }
        try {
            val cases = listOf(
                byteArrayOf('O'.code.toByte(), 'g'.code.toByte(), 'g'.code.toByte(), 'S'.code.toByte()),
                PcmWav.encode(ByteArray(16), 16_000),
                ByteArray(256) { 0x55 },
            )
            cases.forEachIndexed { index, bytes ->
                val written = DownloadedSessionAudioPersist.persistToDirectory(
                    decryptedBytes = bytes,
                    destDirectory = ModelStoreDir(dir.absolutePath),
                    fileBaseName = "case-$index",
                )
                assertTrue(
                    written.extension == "ogg" || written.extension == "wav",
                    "got ${written.extension}",
                )
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    private suspend fun persistAndProcess(
        decrypted: ByteArray,
        recordingId: String,
        existingTranscript: String? = null,
    ): JobProgress {
        val dir = File.createTempFile("velum-persist-$recordingId", null).apply {
            delete()
            mkdirs()
        }
        val written = DownloadedSessionAudioPersist.persistToDirectory(
            decryptedBytes = decrypted,
            destDirectory = ModelStoreDir(dir.absolutePath),
            fileBaseName = recordingId,
        )
        return processExistingFile(
            path = written.absolutePath,
            sizeBytes = written.sizeBytes,
            recordingId = recordingId,
            existingTranscript = existingTranscript,
        )
    }

    private suspend fun processExistingFile(
        path: String,
        sizeBytes: Long,
        recordingId: String,
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
        repo.insertRecording(
            id = recordingId,
            sessionId = 42L,
            folderId = null,
            title = "Downloaded",
            source = "note_pro",
            deviceSerial = "881E2E01",
            startedAt = 1_700_000_000L,
            durationSeconds = 1,
            sizeBytes = sizeBytes,
            localPath = path,
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
