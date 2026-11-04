package dev.velum.ai.pipeline

import dev.velum.ai.audio.createOpusDecoder
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * #86 decode stage from disk (synthetic audio only): WAV PCM copied in slices matches the source,
 * and decode failure, cancellation and a full disk leave no temp PCM and keep the recording file.
 */
class RecordingProcessorDecodeCleanupTest {
    private class Harness(val transcribe: suspend (AudioInput) -> Unit,
        val llmText: suspend () -> String = { "## Summary\nok" },
        val diarizeHook: (AudioInput) -> Unit = {},
        thermal: ThermalPolicy = NoOpThermalPolicy,
        opusDecoder: (() -> dev.velum.ai.audio.OpusDecoder)? = null,
        pcmSpillRoot: ModelStoreDir? = null,
    ) {
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
            override suspend fun diarize(input: AudioInput, modelId: String): DiarizationResult {
                this@Harness.diarizeHook(input)
                return DiarizationResult(spans = emptyList(), engineId = engineId, modelId = modelId)
            }
        }
        private val llm = object : LlmEngine {
            override val engineId = "noop"
            override suspend fun complete(request: LlmRequest, modelId: String) =
                LlmResult(text = this@Harness.llmText(), engineId = engineId, modelId = modelId)
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
            thermal = thermal,
            opusDecoder = opusDecoder ?: ::createOpusDecoder,
            pcmSpillRoot = pcmSpillRoot ?: ModelStoreDir(defaultModelStoreRoot().absolutePath),
        )

        suspend fun insert(id: String, file: File) = repo.insertRecording(
            id = id, sessionId = null, folderId = null, title = "T", source = "note_pro",
            deviceSerial = null, startedAt = 1, durationSeconds = 1, sizeBytes = file.length(),
            localPath = file.path, transcript = null, summaryMarkdown = null, status = "queued",
        )
    }

    private val API = ApiModelOverride(profileId = "work", modelId = "whisper-custom")

    /** The run-scoped spill dir (`pcm-<id>-<run>`) if one exists, otherwise a path that does not. */
    private fun spillDir(id: String): File {
        val tmp = File(ModelStoreDir(defaultModelStoreRoot().absolutePath).resolve("tmp").absolutePath)
        return tmp.listFiles { f -> f.isDirectory && f.name.startsWith("pcm-$id-") }?.firstOrNull()
            ?: File(tmp, "pcm-$id-none")
    }

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

    /** Writes a mono 16 kHz WAV of [seconds] straight to disk (never whole on the heap): header, then 1 s slices. */
    private fun writeWav(file: File, seconds: Int, rate: Int = 16_000) {
        val dataLen = seconds * rate * 2
        val header = wav(ByteArray(0), rate) // header bytes up to and including "data" + len 0
        val prefix = header.copyOf(header.size - 8) // up to just before the data chunk header
        val riffBody = prefix.size - 8 + 8 + dataLen
        file.outputStream().buffered(256 * 1024).use { out ->
            out.write(prefix.copyOf().also { System.arraycopy(le32(riffBody), 0, it, 4, 4) })
            out.write("data".toByteArray()); out.write(le32(dataLen))
            val second = ByteArray(rate * 2) { (it % 251).toByte() }
            repeat(seconds) { out.write(second) }
        }
    }

    private class HeapPeaks(
        var asr: Long = 0, var diarization: Long = 0, var overall: Long = 0, var overallAtFirstAsr: Long = -1,
        var asrCalls: Int = 0, var diarizationBytes: Int = -1, var diarizationRate: Int = -1,
    )

    /** Heap in use after the most recent GC, from the JVM's own bookkeeping (not a racy used-minus-free read). */
    private fun heapAfterGc(): Long {
        System.gc()
        return java.lang.management.ManagementFactory.getMemoryPoolMXBeans()
            .filter { it.type == java.lang.management.MemoryType.HEAP }
            .sumOf { it.collectionUsage?.used ?: 0L }
    }

    /**
     * Retained heap above the pre-run baseline while a [seconds]-long WAV is processed. A background sampler (the
     * same technique as HeapVsDurationDecodeTest) covers the whole run including the copy to disk, and hooks sample
     * at each ASR window and when diarization receives its audio. Peaks are kept apart so the one deliberate
     * diarization array cannot hide growth anywhere else.
     */
    private fun heapProcessingWav(seconds: Int, rate: Int = 16_000): HeapPeaks = runBlocking {
        val src = File.createTempFile("velum-86-heap", ".wav").apply { deleteOnExit() }
        val peaks = HeapPeaks()
        val overall = java.util.concurrent.atomic.AtomicLong(0L)
        val running = java.util.concurrent.atomic.AtomicBoolean(false)
        var sampler: Thread? = null
        try {
            writeWav(src, seconds, rate)
            val h = Harness(
                { input ->
                    peaks.asrCalls++
                    peaks.asr = maxOf(peaks.asr, heapAfterGc() - heapBaseline)
                    if (peaks.overallAtFirstAsr < 0) peaks.overallAtFirstAsr = overall.get() // the copy is done by now
                    java.lang.ref.Reference.reachabilityFence(input)
                },
                diarizeHook = { input ->
                    peaks.diarization = maxOf(peaks.diarization, heapAfterGc() - heapBaseline)
                    peaks.diarizationBytes = input.bytes.size
                    peaks.diarizationRate = input.sampleRateHz
                    java.lang.ref.Reference.reachabilityFence(input)
                },
            )
            h.insert("rec-86-heap-$seconds", src)
            heapBaseline = heapAfterGc()
            running.set(true)
            sampler = Thread {
                while (running.get()) {
                    overall.accumulateAndGet(heapAfterGc() - heapBaseline) { x, y -> maxOf(x, y) }
                    Thread.sleep(20)
                }
            }.apply { isDaemon = true; start() }
            val events = h.processor.process("rec-86-heap-$seconds", templateId = "velum.meeting", apiModelOverride = API).toList()
            assertTrue(events.last() is JobProgress.Succeeded, "last=${events.last()}")
        } finally {
            running.set(false)
            sampler?.join(5_000)
            src.delete()
        }
        peaks.overall = overall.get()
        peaks
    }

    @Volatile private var heapBaseline = 0L

    @Test
    fun wav_processing_heap_stays_bounded_as_the_recording_gets_longer() {
        val forty = heapProcessingWav(40 * 60)
        val ninety = heapProcessingWav(90 * 60) // ~165 MiB of PCM on disk
        println(
            "wav-process heap KiB: 40min asr=${forty.asr shr 10} diar=${forty.diarization shr 10} overall=${forty.overall shr 10}; " +
                "90min asr=${ninety.asr shr 10} diar=${ninety.diarization shr 10} overall=${ninety.overall shr 10}; " +
                "asr calls ${forty.asrCalls}/${ninety.asrCalls}",
        )
        // The hooks really ran: windows of the whole recording reached ASR, and diarization got its 30 min array.
        assertTrue(forty.asrCalls > 1 && ninety.asrCalls > forty.asrCalls, "ASR windows: ${forty.asrCalls} / ${ninety.asrCalls}")
        assertEquals(DIARIZATION_MAX_BYTES, forty.diarizationBytes)
        assertEquals(DIARIZATION_MAX_BYTES, ninety.diarizationBytes)
        // ASR windows (and the copy to disk) stay small; only the diarization array is large, and it is capped.
        assertTrue(ninety.diarization > 50L shl 20, "the sampler saw the diarization array (${ninety.diarization shr 20} MiB)")
        assertTrue(ninety.asr < 8L shl 20, "ASR stage retained ${ninety.asr shr 20} MiB")
        // Everything before the first ASR window (decode, the copy to disk) kept the heap flat too.
        assertTrue(ninety.overallAtFirstAsr in 0 until (8L shl 20), "copy stage retained ${ninety.overallAtFirstAsr shr 20} MiB")
        assertTrue(ninety.diarization < 72L shl 20, "diarization stage retained ${ninety.diarization shr 20} MiB")
        assertTrue(ninety.diarization - forty.diarization < 12L shl 20, "diarization heap grew with duration")
        // Whole-run peak (sampled during the copy too): the 30 min array plus a small margin, never the full PCM.
        assertTrue(ninety.overall < 72L shl 20, "whole-run peak ${ninety.overall shr 20} MiB (full PCM is ~165 MiB)")
    }

    @Test
    fun diarization_array_is_capped_in_bytes_not_only_in_seconds_at_48_khz() {
        // 11 min at 48 kHz mono = 63.4 MB of PCM (spills); 30 min in seconds would be 172.8 MB.
        val peaks = heapProcessingWav(11 * 60, rate = 48_000)
        assertEquals(48_000, peaks.diarizationRate)
        assertEquals(DIARIZATION_MAX_BYTES, peaks.diarizationBytes)
    }

    @Test
    fun a_wav_chunk_size_of_0xFFFFFFF8_cannot_hang_the_job() {
        // "JUNK" with size 0xFFFFFFF8 (-8 as a signed Int) makes a signed walk stay on the same offset forever.
        val fmt = "fmt ".toByteArray() + le32(16) + le16(1) + le16(1) + le32(16_000) + le32(32_000) + le16(2) + le16(16)
        val junk = "JUNK".toByteArray() + byteArrayOf(0xF8.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        val body = "WAVE".toByteArray() + fmt + junk + ByteArray(4_000)
        val bytes = "RIFF".toByteArray() + le32(body.size) + body
        val src = tempAudio(".wav", bytes)
        var last: JobProgress? = null
        val worker = Thread {
            val h = Harness({ error("ASR must not run") })
            runBlocking {
                h.insert("rec-86-hang", src)
                last = h.processor.process("rec-86-hang", templateId = "velum.meeting", apiModelOverride = API).toList().last()
            }
        }.apply { isDaemon = true; start() }
        worker.join(20_000)
        assertFalse(worker.isAlive, "the WAV chunk walk did not terminate")
        assertEquals(WAV_NO_DATA_MESSAGE, (last as JobProgress.Failed).message)
        assertContentEquals(bytes, src.readBytes(), "source recording preserved")
    }

    @Test
    fun wav_without_a_data_chunk_fails_explicitly_cleans_temp_pcm_and_keeps_source() = runBlocking {
        // Valid RIFF/WAVE and fmt chunks, but no data chunk: nothing to decode, and it must not be read as PCM.
        val fmt = "fmt ".toByteArray() + le32(16) + le16(1) + le16(1) + le32(16_000) + le32(32_000) + le16(2) + le16(16)
        val junk = "LIST".toByteArray() + le32(8) + ByteArray(8)
        val body = "WAVE".toByteArray() + fmt + junk + ByteArray(2_000) // trailing bytes that are not a chunk
        val bytes = "RIFF".toByteArray() + le32(body.size) + body
        var asrRan = false
        val h = Harness({ asrRan = true })
        val src = tempAudio(".wav", bytes)
        h.insert("rec-86-nodata", src)
        val events = h.processor.process("rec-86-nodata", templateId = "velum.meeting", apiModelOverride = API).toList()
        assertEquals(WAV_NO_DATA_MESSAGE, (events.last() as JobProgress.Failed).message)
        assertFalse(asrRan, "ASR must not run on a WAV with no audio")
        assertEquals("failed", h.repo.getRecording("rec-86-nodata")!!.status)
        assertEquals(WAV_NO_DATA_MESSAGE, h.repo.getProcessingError("rec-86-nodata"), "the saved error is the same message")
        assertFalse(spillDir("rec-86-nodata").exists())
        assertContentEquals(bytes, src.readBytes(), "source recording preserved")
    }

    private val fmt16k get() = "fmt ".toByteArray() + le32(16) + le16(1) + le16(1) + le32(16_000) + le32(32_000) + le16(2) + le16(16)
    private fun riff(vararg chunks: ByteArray): ByteArray {
        val body = "WAVE".toByteArray() + chunks.fold(ByteArray(0)) { a, c -> a + c }
        return "RIFF".toByteArray() + le32(body.size) + body
    }
    private fun chunk(id: String, payload: ByteArray) = id.toByteArray() + le32(payload.size) + payload

    /** Runs [bytes] (as a [ext] file) through the processor; returns the last event and the audio ASR received. */
    private fun runAudio(id: String, ext: String, bytes: ByteArray): Triple<JobProgress, List<AudioInput>, Harness> = runBlocking {
        val captured = mutableListOf<AudioInput>()
        val h = Harness({ captured += it })
        val src = tempAudio(ext, bytes)
        h.insert(id, src)
        val last = h.processor.process(id, templateId = "velum.meeting", apiModelOverride = API).toList().last()
        assertContentEquals(bytes, src.readBytes(), "source recording preserved")
        assertFalse(spillDir(id).exists(), "temp PCM removed")
        Triple(last, captured, h)
    }

    @Test
    fun wav_data_chunk_after_more_than_2_MiB_of_other_chunks_is_decoded() {
        val pcm = ByteArray(16_000 * 2) { (it % 97).toByte() }
        val bytes = riff(fmt16k, chunk("LIST", ByteArray(3 * 1024 * 1024)), chunk("data", pcm))
        val (last, captured, _) = runAudio("rec-86-latedata", ".wav", bytes)
        assertTrue(last is JobProgress.Succeeded, "last=$last")
        assertContentEquals(pcm, captured.single().bytes)
    }

    @Test
    fun wav_with_no_data_chunk_in_a_large_file_fails_with_the_saved_message() {
        val bytes = riff(fmt16k, chunk("LIST", ByteArray(3 * 1024 * 1024)))
        val (last, captured, h) = runAudio("rec-86-nodata-big", ".wav", bytes)
        assertEquals(WAV_NO_DATA_MESSAGE, (last as JobProgress.Failed).message)
        assertEquals(WAV_NO_DATA_MESSAGE, runBlocking { h.repo.getProcessingError("rec-86-nodata-big") })
        assertTrue(captured.isEmpty())
    }

    @Test
    fun rf64_is_named_as_unsupported_without_a_path_whatever_the_extension() {
        val rf64 = "RF64".toByteArray() + le32(-1) + "WAVE".toByteArray() + "ds64".toByteArray() + le32(28) + ByteArray(28) +
            fmt16k + chunk("data", ByteArray(3_200))
        for (ext in listOf(".wav", ".bin")) {
            val (last, captured, _) = runAudio("rec-86-rf64$ext", ext, rf64)
            val message = (last as JobProgress.Failed).message
            assertTrue(message.startsWith("Unsupported audio format (RF64"), message)
            assertFalse("velum-86-decode" in message, "no file path in the message: $message")
            assertTrue(captured.isEmpty(), "RF64 must not be transcribed as raw PCM")
        }
    }

    @Test
    fun wav_data_size_zero_with_trailing_audio_reads_to_the_end_and_empty_data_fails() {
        val pcm = ByteArray(32_000) { (it % 89).toByte() }
        val streaming = riff(fmt16k) + "data".toByteArray() + le32(0) + pcm // header never finalized
        val (ok, captured, _) = runAudio("rec-86-size0", ".wav", streaming)
        assertTrue(ok is JobProgress.Succeeded, "last=$ok")
        assertContentEquals(pcm, captured.single().bytes)
        val (empty, _, _) = runAudio("rec-86-empty", ".wav", riff(fmt16k, chunk("data", ByteArray(0))))
        assertEquals(WAV_NO_DATA_MESSAGE, (empty as JobProgress.Failed).message)
    }

    @Test
    fun device_crc_mismatch_warning_survives_processing_and_wins_over_truncation() = runBlocking {
        val pcm = ByteArray(16_000 * 2) // 1 s
        val h = Harness({ })
        val src = tempAudio(".wav", wav(pcm))
        h.insert("rec-212-crc", src)
        h.repo.setProcessingWarning("rec-212-crc", dev.velum.core.model.ProcessingWarning.DEVICE_CRC_MISMATCH)
        val events = h.processor.process("rec-212-crc", templateId = "velum.meeting", apiModelOverride = API).toList()
        assertTrue(events.last() is JobProgress.Succeeded, "last=${events.last()}")
        assertEquals(dev.velum.core.model.ProcessingWarning.DEVICE_CRC_MISMATCH, h.repo.getRecording("rec-212-crc")!!.processing_warning)
    }

    @Test
    fun a_crc_mismatch_written_while_the_job_runs_is_not_overwritten() = runBlocking {
        val src = tempAudio(".wav", wav(ByteArray(16_000 * 2)))
        lateinit var h: Harness
        // The thermal check runs after the job read its row and before it writes the warning.
        var first = true // only the first check precedes the warning write; later stages must not rewrite it
        val thermal = object : ThermalPolicy {
            override fun isHot(): Boolean {
                if (first) runBlocking { h.repo.setProcessingWarning("rec-212-late", dev.velum.core.model.ProcessingWarning.DEVICE_CRC_MISMATCH) }
                first = false
                return false
            }
            override suspend fun awaitCool(onPaused: suspend (message: String) -> Unit) = Unit
        }
        h = Harness({ }, thermal = thermal)
        h.insert("rec-212-late", src)
        val events = h.processor.process("rec-212-late", templateId = "velum.meeting", apiModelOverride = API).toList()
        assertTrue(events.last() is JobProgress.Succeeded, "last=${events.last()}")
        assertEquals(dev.velum.core.model.ProcessingWarning.DEVICE_CRC_MISMATCH, h.repo.getRecording("rec-212-late")!!.processing_warning)
    }

    @Test
    fun unknown_extension_with_ogg_bytes_streams_through_the_path_decoder() = runBlocking {
        var decodedPath: String? = null
        val decoder = object : dev.velum.ai.audio.OpusDecoder {
            override fun decodeToPcm(oggBytes: ByteArray) = error("the whole file must not be read")
            override fun decodeToPcmFile(oggBytes: ByteArray, dest: ModelStoreDir) = error("the whole file must not be read")
            override fun decodePathToPcmFile(path: String, dest: ModelStoreDir, checkCancelled: () -> Unit): dev.velum.ai.audio.SpilledPcmMeta {
                decodedPath = path
                dest.writeBytes(ByteArray(640))
                return dev.velum.ai.audio.SpilledPcmMeta(sampleRateHz = 16_000, channels = 1, sizeBytes = 640)
            }
        }
        val captured = mutableListOf<AudioInput>()
        val h = Harness({ captured += it }, opusDecoder = { decoder })
        val src = tempAudio(".bin", "OggS".toByteArray() + ByteArray(60))
        h.insert("rec-86-sniff-ogg", src)
        val events = h.processor.process("rec-86-sniff-ogg", templateId = "velum.meeting", apiModelOverride = API).toList()
        assertTrue(events.last() is JobProgress.Succeeded, "last=${events.last()}")
        assertEquals(src.path, decodedPath)
        assertEquals(640, captured.single().bytes.size)
    }

    @Test
    fun unknown_extension_with_wav_bytes_copies_the_pcm_in_slices() = runBlocking {
        val pcm = ByteArray(20 * 16_000 * 2) { (it % 251).toByte() }
        val captured = mutableListOf<AudioInput>()
        val h = Harness({ captured += it })
        val src = tempAudio(".bin", wav(pcm))
        h.insert("rec-86-sniff-wav", src)
        val events = h.processor.process("rec-86-sniff-wav", templateId = "velum.meeting", apiModelOverride = API).toList()
        assertTrue(events.last() is JobProgress.Succeeded, "last=${events.last()}")
        assertContentEquals(pcm, captured.single().bytes)
        assertEquals(16_000, captured.single().sampleRateHz)
        assertFalse(spillDir("rec-86-sniff-wav").exists())
    }

    @Test
    fun unknown_extension_with_no_container_is_copied_as_raw_16k_pcm_across_slices() = runBlocking {
        val raw = ByteArray(300_000) { (it % 97).toByte() } // more than one 256 KiB slice
        val captured = mutableListOf<AudioInput>()
        val h = Harness({ captured += it })
        val src = tempAudio(".bin", raw)
        h.insert("rec-86-raw", src)
        val events = h.processor.process("rec-86-raw", templateId = "velum.meeting", apiModelOverride = API).toList()
        assertTrue(events.last() is JobProgress.Succeeded, "last=${events.last()}")
        assertContentEquals(raw, captured.single().bytes)
        assertEquals(16_000, captured.single().sampleRateHz)
    }

    @Test
    fun known_unsupported_containers_in_a_bin_fail_plainly_instead_of_becoming_noise() = runBlocking {
        val heads = mapOf(
            "MP3" to "ID3".toByteArray() + byteArrayOf(4, 0, 0) + ByteArray(40),
            "MP4/M4A" to byteArrayOf(0, 0, 0, 0x20) + "ftypM4A ".toByteArray() + ByteArray(40),
            "FLAC" to "fLaC".toByteArray() + ByteArray(40),
            "RIFF (not WAVE)" to "RIFF".toByteArray() + ByteArray(4) + "AVI ".toByteArray() + ByteArray(40),
            "AMR" to "#!AMR\n".toByteArray() + ByteArray(40),
            "WebM/Matroska" to byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()) + ByteArray(40),
        )
        for ((name, bytes) in heads) {
            var asrRan = false
            val h = Harness({ asrRan = true })
            val src = tempAudio(".bin", bytes)
            h.insert("rec-86-container", src)
            val events = h.processor.process("rec-86-container", templateId = "velum.meeting", apiModelOverride = API).toList()
            val failed = events.last() as JobProgress.Failed
            assertTrue(failed.message.startsWith("Unsupported audio format"), "$name: ${failed.message}")
            assertFalse(asrRan, "$name must not reach ASR as PCM")
            assertContentEquals(bytes, src.readBytes(), "source recording preserved")
            assertFalse(spillDir("rec-86-container").exists())
        }
    }

    @Test
    fun raw_pcm_that_starts_like_an_mp3_or_adts_sync_word_is_still_pcm() = runBlocking {
        // FF FB is the sample -1025 and FF F1 is -3585: ordinary audio, never a container.
        val starts = listOf(
            byteArrayOf(0x12, 0x34),
            byteArrayOf(0xFF.toByte(), 0xFB.toByte()),
            byteArrayOf(0xFF.toByte(), 0xF1.toByte()),
            byteArrayOf(0xFF.toByte(), 0xF3.toByte()),
        )
        for ((i, start) in starts.withIndex()) {
            val pcm = start + ByteArray(2_000) { if (it % 2 == 0) 0x12 else 0x34 }
            val captured = mutableListOf<AudioInput>()
            val h = Harness({ captured += it })
            val src = tempAudio(".bin", pcm)
            h.insert("rec-86-loud-$i", src)
            val events = h.processor.process("rec-86-loud-$i", templateId = "velum.meeting", apiModelOverride = API).toList()
            assertTrue(events.last() is JobProgress.Succeeded, "start $i: last=${events.last()}")
            assertContentEquals(pcm, captured.single().bytes, "start $i")
        }
    }

    @Test
    fun a_file_over_the_spill_limit_fails_before_any_pcm_is_written() = runBlocking {
        // Sparse: setLength reserves no disk. The size check must reject it before the slice copy starts.
        val huge = File.createTempFile("velum-86-huge", ".bin").apply {
            deleteOnExit()
            java.io.RandomAccessFile(this, "rw").use { it.setLength(PcmAudioBuffer.MAX_SPILL_BYTES + 1) }
        }
        val h = Harness({ error("ASR must not run") })
        h.insert("rec-86-huge", huge)
        val events = h.processor.process("rec-86-huge", templateId = "velum.meeting", apiModelOverride = API).toList()
        val failed = events.last() as JobProgress.Failed
        assertTrue(failed.message.contains("too large"), failed.message)
        assertFalse(File(spillDir("rec-86-huge"), "pcm.s16le").exists() && File(spillDir("rec-86-huge"), "pcm.s16le").length() > 0)
        assertFalse(spillDir("rec-86-huge").exists())
        assertTrue(huge.isFile, "source recording preserved")
    }

    @Test
    fun corrupt_ogg_fails_explicitly_cleans_temp_pcm_and_keeps_source() = runBlocking {
        // Valid header + one page of audio, then a page with a bad magic: PCM is written before the failure.
        val packet = dev.velum.ai.audio.SyntheticOgg.audioPacket(stereo = false)
        val bytes = dev.velum.ai.audio.SyntheticOgg.page(listOf(dev.velum.ai.audio.SyntheticOgg.opusHead(1, 48_000)), 0, 2) +
            dev.velum.ai.audio.SyntheticOgg.page(listOf(dev.velum.ai.audio.SyntheticOgg.tags()), 1) +
            dev.velum.ai.audio.SyntheticOgg.page(List(50) { packet }, 2) +
            "XggS".toByteArray() + ByteArray(40)
        var asrRan = false
        val h = Harness({ asrRan = true })
        val src = tempAudio(".ogg", bytes)
        h.insert("rec-86-corrupt", src)
        val events = h.processor.process("rec-86-corrupt", templateId = "velum.meeting", apiModelOverride = API).toList()
        val failed = events.last() as JobProgress.Failed
        assertTrue(failed.message.contains("Invalid Ogg page magic"), "message=${failed.message}")
        assertFalse(asrRan, "ASR must not run on undecodable audio")
        // The same input through the decoder alone: one full page of 20 ms packets is on disk before the throw.
        val pcm = File.createTempFile("velum-86-corrupt", ".s16le").apply { deleteOnExit() }
        val ex = kotlin.runCatching {
            dev.velum.ai.audio.KopusOpusDecoder().decodePathToPcmFile(src.path, ModelStoreDir(pcm.path))
        }.exceptionOrNull()
        assertTrue(ex != null && ex.message.orEmpty().contains("Invalid Ogg page magic"), "ex=$ex")
        assertEquals(50L * 640, pcm.length(), "PCM from the valid page was written before the failure")
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
        withTimeout(30_000) { entered.await() }
        assertTrue(File(spillDir("rec-86-cancel"), "pcm.s16le").isFile, "decode wrote temp PCM before cancel")
        job.cancelAndJoin()
        assertFalse(spillDir("rec-86-cancel").exists(), "temp PCM removed on cancellation")
        assertContentEquals(bytes, src.readBytes())
        assertEquals("failed", h.repo.getRecording("rec-86-cancel")!!.status)
    }

    @Test
    fun cancelled_job_leaves_the_queued_status_a_replacing_enqueue_wrote() = runBlocking {
        val bytes = wav(ByteArray(64_000))
        val entered = CompletableDeferred<Unit>()
        val h = Harness({ entered.complete(Unit); awaitCancellation() })
        val src = tempAudio(".wav", bytes)
        h.insert("rec-324", src)
        val job = launch(Dispatchers.Default) { h.processor.process("rec-324", templateId = "velum.meeting", apiModelOverride = API).toList() }
        withTimeout(30_000) { entered.await() }
        // AutoFlow's runner marks the row queued and enqueues a replacement (REPLACE) that cancels this job.
        h.repo.updateStatus("rec-324", "queued")
        job.cancelAndJoin()
        assertEquals("queued", h.repo.getRecording("rec-324")!!.status, "the cancelled job overwrote the replacement's status")
    }

    @Test
    fun old_job_unwinding_after_its_replacement_started_leaves_the_replacements_generating_status() = runBlocking {
        val bytes = wav(ByteArray(64_000))
        val entered = java.util.concurrent.atomic.AtomicInteger()
        val second = CompletableDeferred<Unit>()
        val h = Harness({ if (entered.incrementAndGet() == 2) second.complete(Unit); awaitCancellation() })
        val src = tempAudio(".wav", bytes)
        h.insert("rec-324b", src)
        val first = launch(Dispatchers.Default) { h.processor.process("rec-324b", templateId = "velum.meeting", apiModelOverride = API).toList() }
        withTimeout(30_000) { while (entered.get() < 1) kotlinx.coroutines.delay(20) }
        // WorkManager REPLACE: the replacement is already running (generating) when the old job unwinds.
        val replacement = launch(Dispatchers.Default) { h.processor.process("rec-324b", templateId = "velum.meeting", apiModelOverride = API).toList() }
        withTimeout(30_000) { second.await() }
        first.cancelAndJoin()
        assertEquals("generating", h.repo.getRecording("rec-324b")!!.status, "the old job overwrote the replacement's status")
        replacement.cancelAndJoin()
        assertEquals("failed", h.repo.getRecording("rec-324b")!!.status)
    }

    /** Runs A until [gateA] opens, lets B run to the end meanwhile, then releases A: A is a stale (replaced) run. */
    private suspend fun staleRunScenario(
        id: String,
        deleteAudio: Boolean,
        failA: Boolean,
        check: suspend (Harness, java.io.File, List<JobProgress>) -> Unit,
    ) = kotlinx.coroutines.coroutineScope {
        val gateA = CompletableDeferred<Unit>()
        val enteredA = CompletableDeferred<Unit>()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val llmCalls = java.util.concurrent.atomic.AtomicInteger()
        val h = Harness({
            if (calls.incrementAndGet() == 1) {
                enteredA.complete(Unit)
                gateA.await()
                if (failA) error("stale run failed")
            }
        }, llmText = { "## Summary\nrun-${llmCalls.incrementAndGet()}" })
        if (deleteAudio) h.settings.setDeleteAudioAfterProcessing(true)
        val src = tempAudio(".wav", wav(ByteArray(64_000)))
        h.insert(id, src)
        val aEvents = java.util.concurrent.CopyOnWriteArrayList<JobProgress>()
        val a = launch(Dispatchers.Default) { h.processor.process(id, templateId = "velum.meeting", apiModelOverride = API).collect { aEvents += it } }
        withTimeout(30_000) { enteredA.await() }
        // The replacement runs to the end while A is still in flight.
        h.processor.process(id, templateId = "velum.meeting", apiModelOverride = API).toList()
        gateA.complete(Unit)
        a.join()
        check(h, src, aEvents.toList())
    }

    @Test
    fun stale_success_after_the_replacement_published_writes_nothing() = runBlocking {
        staleRunScenario("rec-324-stale-ok", deleteAudio = true, failA = false) { h, src, aEvents ->
            val row = h.repo.getRecording("rec-324-stale-ok")!!
            assertEquals("ready", row.status)
            val versions = h.repo.observeSummaryVersions("rec-324-stale-ok").first()
            assertEquals(1, versions.size, "only the replacement's version exists: $versions")
            assertTrue(versions.single().is_active == 1L)
            // The replacement summarized first (run-1); the stale run (run-2) must not have replaced it.
            assertEquals("## Summary\nrun-1", versions.single().markdown)
            assertEquals("## Summary\nrun-1", row.summary_markdown)
            assertTrue(aEvents.none { it is JobProgress.Succeeded || it is JobProgress.Failed }, "stale run reported: $aEvents")
            assertFalse(src.exists(), "the newest run deleted the audio once")
        }
    }

    @Test
    fun stale_failure_after_the_replacement_published_writes_nothing() = runBlocking {
        staleRunScenario("rec-324-stale-fail", deleteAudio = false, failA = true) { h, _, aEvents ->
            val row = h.repo.getRecording("rec-324-stale-fail")!!
            assertEquals("ready", row.status, "a stale failure overwrote the replacement's status")
            assertEquals(null, row.processing_warning)
            assertTrue(aEvents.none { it is JobProgress.Failed }, "stale run reported failure: $aEvents")
        }
    }

    @Test
    fun a_cancelled_rerun_after_a_normal_finish_restores_the_ready_status() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val h = Harness({ if (calls.incrementAndGet() == 2) { entered.complete(Unit); awaitCancellation() } })
        val src = tempAudio(".wav", wav(ByteArray(64_000)))
        h.insert("rec-324-rerun", src)
        h.processor.process("rec-324-rerun", templateId = "velum.meeting", apiModelOverride = API).toList()
        assertEquals("ready", h.repo.getRecording("rec-324-rerun")!!.status)
        val job = launch(Dispatchers.Default) { h.processor.process("rec-324-rerun", templateId = "velum.meeting", apiModelOverride = API).toList() }
        withTimeout(30_000) { entered.await() }
        job.cancelAndJoin()
        assertEquals("ready", h.repo.getRecording("rec-324-rerun")!!.status, "the finished run's token must not block the restore")
    }

    @Test
    fun a_collector_that_stops_right_after_the_success_write_leaves_ready_in_place() = runBlocking {
        val h = Harness({})
        h.settings.setDeleteAudioAfterProcessing(true)
        val src = tempAudio(".wav", wav(ByteArray(64_000)))
        h.insert("rec-324-late-cancel", src)
        h.repo.updateStatus("rec-324-late-cancel", "imported") // the status a wrongful restore would write back
        var cancelled = false
        val job = launch(Dispatchers.Default) {
            h.processor.process("rec-324-late-cancel", templateId = "velum.meeting", apiModelOverride = API).collect {
                // The success write and the audio delete are done; stop the collector at the next emit.
                if (it is JobProgress.Running && it.message.startsWith("Removed")) {
                    cancelled = true
                    throw CancellationException("collector gone")
                }
            }
        }
        job.join()
        assertTrue(cancelled, "the collector was never stopped, so the late-cancel path did not run")
        assertEquals("ready", h.repo.getRecording("rec-324-late-cancel")!!.status, "a late cancel restored the earlier status")
        assertFalse(src.exists(), "the audio was deleted before the emit")
    }

    @Test
    fun a_run_spills_under_the_root_it_was_given_and_the_wipe_helper_finds_it() = runBlocking {
        val root = ModelStoreDir(java.nio.file.Files.createTempDirectory("velum-spill-root").toString())
        val decoding = kotlinx.coroutines.CompletableDeferred<Unit>()
        val h = Harness({ decoding.complete(Unit); awaitCancellation() }, pcmSpillRoot = root)
        h.insert("rec-root-spill", tempAudio(".wav", wav(ByteArray(64_000))))
        val job = launch(Dispatchers.Default) { h.processor.process("rec-root-spill", templateId = "velum.meeting", apiModelOverride = API).collect { } }
        withTimeout(30_000) { decoding.await() }
        val spills = File(root.resolve("tmp").absolutePath).listFiles { f -> f.name.startsWith("pcm-rec-root-spill-") }.orEmpty()
        assertEquals(1, spills.size, "the run did not spill under the given root")
        assertTrue(File(spills.single(), "pcm.s16le").isFile)
        // A process death leaves it there; the wipe removes it through the same root.
        assertTrue(deleteAllPcmSpill(h.processor.pcmSpillRoot))
        assertFalse(spills.single().exists(), "the wipe's helper missed the run's spill")
        job.cancelAndJoin()
        root.deleteRecursively()
    }

    @Test
    fun overlapping_runs_decode_into_separate_spill_dirs() = runBlocking {
        val enteredBoth = java.util.concurrent.atomic.AtomicInteger()
        val h = Harness({ enteredBoth.incrementAndGet(); awaitCancellation() })
        val src = tempAudio(".wav", wav(ByteArray(64_000)))
        h.insert("rec-324-spill", src)
        val tmp = File(ModelStoreDir(defaultModelStoreRoot().absolutePath).resolve("tmp").absolutePath)
        val a = launch(Dispatchers.Default) { h.processor.process("rec-324-spill", templateId = "velum.meeting", apiModelOverride = API).toList() }
        withTimeout(30_000) { while (enteredBoth.get() < 1) kotlinx.coroutines.delay(20) }
        val b = launch(Dispatchers.Default) { h.processor.process("rec-324-spill", templateId = "velum.meeting", apiModelOverride = API).toList() }
        withTimeout(30_000) { while (enteredBoth.get() < 2) kotlinx.coroutines.delay(20) }
        val dirs = tmp.listFiles { f -> f.isDirectory && f.name.startsWith("pcm-rec-324-spill-") }.orEmpty()
        assertEquals(2, dirs.size, "each run decodes into its own directory: ${dirs.map { it.name }}")
        assertTrue(dirs.all { File(it, "pcm.s16le").isFile }, "both directories were in use during decode")
        a.cancelAndJoin(); b.cancelAndJoin()
        assertEquals(0, tmp.listFiles { f -> f.name.startsWith("pcm-rec-324-spill-") }.orEmpty().size, "both removed")
    }

    @Test
    fun full_disk_reports_insufficient_storage_and_keeps_source() = runBlocking {
        val bytes = ByteArray(1024) { 1 }
        val full = object : dev.velum.ai.audio.OpusDecoder {
            override fun decodeToPcm(oggBytes: ByteArray) = error("unused")
            override fun decodeToPcmFile(oggBytes: ByteArray, dest: ModelStoreDir) = error("unused")
            override fun decodePathToPcmFile(path: String, dest: ModelStoreDir, checkCancelled: () -> Unit): dev.velum.ai.audio.SpilledPcmMeta =
                throw IOException("write failed: ENOSPC (No space left on device)")
        }
        val h = Harness({ error("ASR must not run") }, opusDecoder = { full })
        val src = tempAudio(".ogg", bytes)
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
        org.junit.Assume.assumeTrue("Linux-only: needs /dev/full", devFull.exists())
        val e = kotlin.runCatching { ModelStoreDir(devFull.path).appendBytes(ByteArray(4096)) }.exceptionOrNull()
        assertTrue(e != null && isOutOfStorage(e), "real write failure: $e")
    }

    /** Blocks the worker thread inside the first call until the test has cancelled the job. */
    private class Gate {
        val entered = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        fun pause() { entered.complete(Unit); check(release.await(30, java.util.concurrent.TimeUnit.SECONDS)) }
    }

    private suspend fun cancelWhileBlocked(gate: Gate, id: String, h: Harness, src: File) {
        h.insert(id, src)
        val job = kotlinx.coroutines.GlobalScope.launch(Dispatchers.Default) {
            h.processor.process(id, templateId = "velum.meeting", apiModelOverride = API).toList()
        }
        withTimeout(30_000) { gate.entered.await() }
        job.cancel()
        gate.release.countDown()
        withTimeout(30_000) { job.join() }
    }

    @Test
    fun processor_cancel_during_ogg_decode_stops_before_asr() = runBlocking {
        val gate = Gate()
        var afterCheck = false
        var asrRan = false
        val decoder = object : dev.velum.ai.audio.OpusDecoder {
            override fun decodeToPcm(oggBytes: ByteArray) = error("unused")
            override fun decodeToPcmFile(oggBytes: ByteArray, dest: ModelStoreDir) = error("unused")
            override fun decodePathToPcmFile(path: String, dest: ModelStoreDir, checkCancelled: () -> Unit): dev.velum.ai.audio.SpilledPcmMeta {
                dest.writeBytes(ByteArray(640))
                gate.pause()
                checkCancelled() // the processor's callback must throw once the job is cancelled
                afterCheck = true
                return dev.velum.ai.audio.SpilledPcmMeta(sampleRateHz = 16_000, channels = 1, sizeBytes = 640)
            }
        }
        val h = Harness({ asrRan = true }, opusDecoder = { decoder })
        val srcBytes = ByteArray(64)
        val src = tempAudio(".ogg", srcBytes)
        cancelWhileBlocked(gate, "rec-197-ogg", h, src)
        assertContentEquals(srcBytes, src.readBytes(), "source recording preserved")
        assertEquals("failed", h.repo.getRecording("rec-197-ogg")!!.status)
        assertFalse(afterCheck, "decode kept going after the job was cancelled")
        assertFalse(asrRan)
        assertFalse(spillDir("rec-197-ogg").exists())
    }

    @Test
    fun processor_cancel_before_wav_copy_writes_no_pcm() = runBlocking {
        val gate = Gate()
        // 16 MiB = 64 copy slices. The thermal check runs right before decode and holds the worker.
        val thermal = object : ThermalPolicy {
            override fun isHot(): Boolean { gate.pause(); return false }
            override suspend fun awaitCool(onPaused: suspend (message: String) -> Unit) = Unit
        }
        val h = Harness({ error("ASR must not run") }, thermal = thermal)
        spillDir("rec-197-wav").deleteRecursively() // a leftover from a killed local run would fake growth
        val pcmFile = File(spillDir("rec-197-wav"), "pcm.s16le")
        val maxSeen = java.util.concurrent.atomic.AtomicLong(0)
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val sampler = Thread {
            while (running.get()) { maxSeen.accumulateAndGet(pcmFile.length()) { x, y -> maxOf(x, y) } }
        }.apply { isDaemon = true; start() }
        try {
            val srcBytes = wav(ByteArray(16 shl 20))
            val src = tempAudio(".wav", srcBytes)
            cancelWhileBlocked(gate, "rec-197-wav", h, src)
            assertContentEquals(srcBytes, src.readBytes(), "source recording preserved")
            assertEquals("failed", h.repo.getRecording("rec-197-wav")!!.status)
        } finally {
            running.set(false)
            sampler.join(5_000)
        }
        assertEquals(0L, maxSeen.get(), "PCM was copied after the job was cancelled")
        assertFalse(spillDir("rec-197-wav").exists())
    }

    @Test
    fun sweep_removes_only_stale_spill_dirs_and_keeps_a_running_jobs_file() {
        val root = java.nio.file.Files.createTempDirectory("velum-198-root").toFile()
        try {
            val now = 1_000_000_000_000L
            val cutoff = now - STALE_PCM_SPILL_AGE_MS
            val old = cutoff - 60_000
            fun spill(name: String, fileMtime: Long, dirMtime: Long) = File(root, "tmp/$name").also { dir ->
                dir.mkdirs()
                File(dir, "pcm.s16le").apply { writeBytes(ByteArray(8)); setLastModified(fileMtime) }
                dir.setLastModified(dirMtime)
            }
            spill("pcm-stale", old, old)
            spill("pcm-running", fileMtime = now, dirMtime = old) // old dir, file still being appended to
            spill("pcm-fresh", now, now)
            File(root, "tmp/keep").apply { mkdirs(); setLastModified(old) } // not a spill dir
            assertEquals(1, sweepOrphanPcmSpill(cutoff, ModelStoreDir(root.path)))
            assertFalse(File(root, "tmp/pcm-stale").exists(), "stale spill removed")
            assertTrue(File(root, "tmp/pcm-running/pcm.s16le").isFile, "a running job's file is kept")
            assertTrue(File(root, "tmp/pcm-fresh").isDirectory)
            assertTrue(File(root, "tmp/keep").isDirectory)
            assertEquals(0, sweepOrphanPcmSpill(cutoff, ModelStoreDir(File(root, "absent").path)))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun existing_pcm_file_with_wrong_length_fails_loudly() {
        val f = File.createTempFile("velum-197-len", ".s16le").apply { writeBytes(ByteArray(100)); deleteOnExit() }
        assertFailsWith<IllegalStateException> { PcmAudioBuffer.fromExistingFile(ModelStoreDir(f.path), 200L) }
        assertEquals(100L, PcmAudioBuffer.fromExistingFile(ModelStoreDir(f.path), 100L).sizeBytes)
    }

    @Test
    fun provider_error_text_is_not_reported_as_local_storage() {
        assertFalse(isOutOfStorage(dev.velum.ai.AiApiException(507, "No space left on device")))
        assertTrue(isOutOfStorage(IOException("write failed: ENOSPC")))
    }

    @Test
    fun wrapped_provider_error_text_is_not_reported_as_local_storage() {
        val remote = dev.velum.ai.AiApiException(507, "No space left on device")
        // RuntimeException(cause) copies cause.toString() into its own message.
        assertFalse(isOutOfStorage(RuntimeException(remote)))
        assertFalse(isOutOfStorage(IllegalStateException("provider failed: $remote", remote)))
    }

    @Test
    fun cancellation_check_aborts_ogg_decode_between_packets() {
        val packet = dev.velum.ai.audio.SyntheticOgg.audioPacket(stereo = false)
        val dir = java.nio.file.Files.createTempDirectory("velum-86-cancel").toFile()
        try {
            val ogg = File(dir, "a.ogg").apply {
                writeBytes(
                    dev.velum.ai.audio.SyntheticOgg.page(listOf(dev.velum.ai.audio.SyntheticOgg.opusHead(1, 48_000)), 0, 2) +
                        dev.velum.ai.audio.SyntheticOgg.page(listOf(dev.velum.ai.audio.SyntheticOgg.tags()), 1) +
                        dev.velum.ai.audio.SyntheticOgg.page(List(50) { packet }, 2),
                )
            }
            var packets = 0
            val e = kotlin.runCatching {
                dev.velum.ai.audio.KopusOpusDecoder().decodePathToPcmFile(ogg.path, ModelStoreDir(File(dir, "o.s16le").path)) {
                    if (++packets == 3) throw kotlinx.coroutines.CancellationException("cancel")
                }
            }.exceptionOrNull()
            assertTrue(e is kotlinx.coroutines.CancellationException, "e=$e")
            assertEquals(3, packets, "decode stopped at the cancelled packet")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun model_store_writes_through_a_symlinked_directory() {
        val dir = java.nio.file.Files.createTempDirectory("velum-86-link").toFile()
        try {
            val real = File(dir, "real").apply { mkdirs() }
            val link = File(dir, "link")
            java.nio.file.Files.createSymbolicLink(link.toPath(), real.toPath())
            ModelStoreDir(link.path).createDirectories()
            ModelStoreDir(File(link, "x.bin").path).appendBytes(byteArrayOf(1, 2))
            assertContentEquals(byteArrayOf(1, 2), File(real, "x.bin").readBytes())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun out_of_storage_detection_walks_causes_only() {
        assertTrue(isOutOfStorage(RuntimeException("wrap", IOException("No space left on device"))))
        assertFalse(isOutOfStorage(IOException("Permission denied")))
    }

    @Test
    fun empty_data_chunk_followed_by_another_chunk_does_not_copy_that_chunk_as_audio() {
        val bytes = riff(fmt16k, chunk("data", ByteArray(0)), chunk("LIST", ByteArray(4_000) { 7 }))
        val (last, captured, _) = runAudio("rec-86-emptythenlist", ".wav", bytes)
        assertEquals(WAV_NO_DATA_MESSAGE, (last as JobProgress.Failed).message)
        assertTrue(captured.isEmpty(), "ASR must not receive the LIST chunk as PCM")
    }

    @Test
    fun data_size_is_clamped_to_the_file_for_0xFFFFFFFF_and_for_a_declared_size_past_the_end() {
        val pcm = ByteArray(32_000) { (it % 83).toByte() }
        val streaming = riff(fmt16k) + "data".toByteArray() + byteArrayOf(-1, -1, -1, -1) + pcm
        val (a, capturedA, _) = runAudio("rec-86-ffff", ".wav", streaming)
        assertTrue(a is JobProgress.Succeeded, "last=$a")
        assertContentEquals(pcm, capturedA.single().bytes)
        val truncated = riff(fmt16k) + "data".toByteArray() + le32(1_000_000) + pcm
        val (b, capturedB, _) = runAudio("rec-86-trunc", ".wav", truncated)
        assertTrue(b is JobProgress.Succeeded, "last=$b")
        assertContentEquals(pcm, capturedB.single().bytes)
    }

    @Test
    fun more_than_4096_chunks_before_the_data_chunk_is_treated_as_no_data() {
        val tiny = chunk("JUNK", ByteArray(0))
        val many = ByteArray(0).let { var acc = it; repeat(4_097) { acc += tiny }; acc }
        val bytes = riff(fmt16k) + many + chunk("data", ByteArray(3_200))
        val (last, captured, _) = runAudio("rec-86-manychunks", ".wav", bytes)
        assertEquals(WAV_NO_DATA_MESSAGE, (last as JobProgress.Failed).message)
        assertTrue(captured.isEmpty())
    }

    @Test
    fun an_empty_audio_file_does_not_silently_reuse_the_stored_transcript() = runBlocking {
        for ((ext, bytes) in listOf(".wav" to riff(fmt16k, chunk("data", ByteArray(0))), ".bin" to ByteArray(0))) {
            val h = Harness({ error("ASR must not run") })
            val src = tempAudio(ext, bytes)
            val id = "rec-86-reuse$ext"
            h.insert(id, src)
            h.repo.updateTranscript(id, "Speaker 1: the old transcript")
            val last = h.processor.process(id, templateId = "velum.meeting", apiModelOverride = API).toList().last()
            assertTrue(last is JobProgress.Failed, "$ext: last=$last")
            assertEquals("Speaker 1: the old transcript", h.repo.getRecording(id)!!.transcript, "$ext: transcript untouched")
        }
    }

    @Test
    fun a_missing_audio_file_fails_without_its_path_in_the_message() = runBlocking {
        val h = Harness({ error("ASR must not run") })
        val gone = File.createTempFile("velum-86-gone", ".wav").also { it.delete() }
        h.repo.insertRecording(
            id = "rec-86-gone", sessionId = null, folderId = null, title = "T", source = "note_pro",
            deviceSerial = null, startedAt = 1, durationSeconds = 1, sizeBytes = 0,
            localPath = gone.path, transcript = null, summaryMarkdown = null, status = "queued",
        )
        val last = h.processor.process("rec-86-gone", templateId = "velum.meeting", apiModelOverride = API).toList().last()
        assertEquals(AUDIO_FILE_MISSING_MESSAGE, (last as JobProgress.Failed).message)
        assertFalse(gone.path in last.message)
    }
}
