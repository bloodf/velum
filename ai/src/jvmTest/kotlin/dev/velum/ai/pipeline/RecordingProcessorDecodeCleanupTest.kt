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
        thermal: ThermalPolicy = NoOpThermalPolicy,
        opusDecoder: (() -> dev.velum.ai.audio.OpusDecoder)? = null,
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
            override suspend fun diarize(input: AudioInput, modelId: String) =
                DiarizationResult(spans = emptyList(), engineId = engineId, modelId = modelId)
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
        val job = launch(Dispatchers.Default) {
            h.processor.process("rec-324-late-cancel", templateId = "velum.meeting", apiModelOverride = API).collect {
                // The success write and the audio delete are done; stop the collector at the next emit.
                if (it is JobProgress.Running && it.message.startsWith("Removed")) throw CancellationException("collector gone")
            }
        }
        job.join()
        assertEquals("ready", h.repo.getRecording("rec-324-late-cancel")!!.status, "a late cancel restored the earlier status")
        assertFalse(src.exists(), "the audio was deleted before the emit")
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
}
