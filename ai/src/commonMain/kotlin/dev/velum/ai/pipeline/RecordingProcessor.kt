package dev.velum.ai.pipeline

import dev.velum.ai.MissingApiKeyException
import dev.velum.ai.audio.createOpusDecoder
import dev.velum.ai.engine.AiTask
import dev.velum.ai.engine.AiTaskRouter
import dev.velum.ai.engine.AudioInput
import dev.velum.ai.engine.DEFAULT_LOCAL_DIARIZATION_MODEL_ID
import dev.velum.ai.engine.DEFAULT_LOCAL_LLM_MODEL_ID
import dev.velum.ai.engine.JobProgress
import dev.velum.ai.engine.Transcriber
import dev.velum.ai.engine.TranscriptionResult
import dev.velum.ai.engine.LlmRequest
import dev.velum.ai.engine.requiredLocalModelId
import dev.velum.ai.local.ModelNotReadyException
import dev.velum.ai.local.ModelStoreDir
import dev.velum.ai.local.defaultModelStoreRoot
import dev.velum.core.data.VelumRepository
import dev.velum.core.model.ComputeMode
import dev.velum.core.settings.AiApiTask
import dev.velum.core.settings.ApiModelOverride
import dev.velum.core.settings.SettingsRepository
import dev.velum.core.templates.BuiltinTemplateCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * End-to-end job: decode → transcribe → diarize → markdown transcript → summarize → DB.
 */
class RecordingProcessor(
    private val aiRouter: AiTaskRouter,
    private val repository: VelumRepository,
    private val settings: SettingsRepository,
    private val readFileBytes: (String) -> ByteArray = { path -> ModelStoreDir(path).readBytes() },
    private val deleteFile: (String) -> Unit = { path ->
        runCatching { ModelStoreDir(path).deleteRecursively() }
    },
    private val thermal: ThermalPolicy = NoOpThermalPolicy,
) {
    fun process(
        recordingId: String,
        templateId: String? = null,
        retranscribe: Boolean = true,
        apiModelOverride: ApiModelOverride? = null,
    ): Flow<JobProgress> = flow {
        emit(JobProgress.Running(0.05f, "Starting"))
        repository.updateStatus(recordingId, "generating")

        try {
            val recording = repository.getRecording(recordingId)
                ?: throw IllegalArgumentException("Recording not found: $recordingId")
            val cfg = settings.settings.value

            val filename: String
            val sampleRate: Int
            val localPath = recording.local_path
            val spillRoot = ModelStoreDir(defaultModelStoreRoot().absolutePath)
                .resolve("tmp")
                .resolve("pcm-$recordingId")
            var pcmBuffer: PcmAudioBuffer = PcmAudioBuffer.Memory(ByteArray(0))
            try {
            if (!localPath.isNullOrBlank()) {
                emit(JobProgress.Running(0.1f, "Decoding audio"))
                awaitThermalGate { emit(it) }
                val raw = readFileBytes(localPath)
                filename = localPath.substringAfterLast('/')
                spillRoot.createDirectories()
                val pcmFile = spillRoot.resolve("pcm.s16le")
                if (filename.endsWith(".ogg", ignoreCase = true) ||
                    filename.endsWith(".opus", ignoreCase = true) ||
                    looksLikeOgg(raw)
                ) {
                    // Stream frame-by-frame to disk — never hold full PCM on the heap.
                    val meta = createOpusDecoder().decodeToPcmFile(raw, pcmFile)
                    sampleRate = meta.sampleRateHz
                    pcmBuffer = PcmAudioBuffer.fromExistingFile(pcmFile, meta.sizeBytes)
                } else if (filename.endsWith(".wav", ignoreCase = true) || looksLikeWav(raw)) {
                    val range = locateWavPcm(raw)
                    sampleRate = range.sampleRateHz
                    val pcmLen = (range.endExclusive - range.start).toLong()
                    pcmBuffer = if (pcmLen > PcmAudioBuffer.IN_MEMORY_BUDGET_BYTES) {
                        // Copy data chunk in slices — avoid a second full-PCM heap array.
                        appendRangeToFile(raw, range.start, range.endExclusive, pcmFile)
                        PcmAudioBuffer.fromExistingFile(pcmFile, pcmLen)
                    } else {
                        val pcm = if (pcmLen == 0L) {
                            ByteArray(0)
                        } else {
                            raw.copyOfRange(range.start, range.endExclusive)
                        }
                        PcmAudioBuffer.fromPcmBytes(pcm, spillRoot)
                    }
                } else {
                    sampleRate = 16_000
                    pcmBuffer = PcmAudioBuffer.fromPcmBytes(raw, spillRoot)
                }
            } else {
                // Demo / seed rows have no audio file — keep existing transcript path for summary-only.
                filename = "missing.ogg"
                sampleRate = 16_000
            }

            val prefs = settings.preferences.value
            val personalization = repository.personalizationPromptFragment(
                includeMemory = prefs.useSavedMemory,
            )

            var transcriptMarkdown = recording.transcript.orEmpty()
            if (retranscribe) {
                if (pcmBuffer.sizeBytes <= 0L) {
                    if (transcriptMarkdown.isBlank()) {
                        throw IllegalStateException(
                            "Recording has no local audio path and no existing transcript.",
                        )
                    }
                    emit(JobProgress.Running(0.35f, "Using existing transcript (no audio file)"))
                } else {
                    emit(JobProgress.Running(0.25f, "Transcribing"))
                    awaitThermalGate { emit(it) }
                    ensureApiKeyIfNeeded(cfg.transcriptionMode, AiApiTask.Transcription, "transcription")
                    val asr = aiRouter.transcriber()
                    val asrModel = when (cfg.transcriptionMode) {
                        ComputeMode.Local -> requiredLocalModelId(AiTask.Transcription, cfg)
                        ComputeMode.Api -> ""
                    }
                    val transcription = transcribeWindowed(
                        asr = asr,
                        asrModel = asrModel,
                        filename = filename,
                        pcm = pcmBuffer,
                        sampleRate = sampleRate,
                        languageHint = cfg.audioLanguage,
                        recordingId = recordingId,
                        initialPrompt = personalization.takeIf { it.isNotBlank() },
                    )

                    emit(JobProgress.Running(0.45f, "Diarizing"))
                    awaitThermalGate { emit(it) }
                    ensureApiKeyIfNeeded(cfg.diarizationMode, AiApiTask.Diarization, "diarization")
                    val diar = aiRouter.diarizer()
                    val diarModel = when (cfg.diarizationMode) {
                        ComputeMode.Local ->
                            cfg.localDiarizationModelId?.takeIf { it.isNotBlank() }
                                ?: DEFAULT_LOCAL_DIARIZATION_MODEL_ID
                        ComputeMode.Api ->
                            settings.resolveApiModel(AiApiTask.Diarization).modelId
                    }
                    val diarization = try {
                        // Prefer in-memory buffer; for spilled PCM diarize the first 30 min window
                        // (full-buffer diarizers cannot take multi-hour arrays without OOM).
                        val diarBytes = pcmBuffer.asFullArrayOrNull()
                            ?: pcmBuffer.slice(
                                0,
                                WindowedAsr.pcmWindowBytes(sampleRate, 30 * 60)
                                    .coerceAtMost(pcmBuffer.sizeBytes.toInt()),
                            )
                        diar.diarize(
                            AudioInput(
                                filename = filename,
                                bytes = diarBytes,
                                sampleRateHz = sampleRate,
                                languageHint = cfg.audioLanguage,
                                recordingId = recordingId,
                            ),
                            diarModel,
                        )
                    } catch (_: ModelNotReadyException) {
                        // Optional when local weights missing — keep ASR text.
                        null
                    } catch (_: UnsupportedOperationException) {
                        // API diarization / JVM-without-native — keep ASR text.
                        null
                    }

                    val mergedSegments = if (diarization != null) {
                        TranscriptFormatter.mergeSpeakerLabels(transcription.segments, diarization)
                    } else {
                        transcription.segments
                    }
                    val withSpeakers = transcription.copy(segments = mergedSegments)
                    transcriptMarkdown = TranscriptFormatter.toMarkdown(withSpeakers, diarization)
                    repository.updateTranscript(recordingId, transcriptMarkdown)

                    if (diarization != null && diarization.spans.isNotEmpty()) {
                        val bySpeaker = diarization.spans.groupBy { it.speakerId }
                        val speakers = bySpeaker.map { (id, spans) ->
                            val talkMs = spans.sumOf { (it.endMs - it.startMs).coerceAtLeast(0) }
                            VelumRepository.SpeakerUpsert(
                                id = "$recordingId-$id",
                                label = id,
                                displayName = diarization.namedSpeakers[id],
                                talkSeconds = talkMs / 1000,
                            )
                        }
                        repository.updateSpeakers(recordingId, speakers)
                    }
                }
            }

            emit(JobProgress.Running(0.7f, "Summarizing"))
            awaitThermalGate { emit(it) }
            ensureApiKeyIfNeeded(cfg.summaryMode, AiApiTask.Summary, "summaries")
            val summaryLang = SummaryLocalization.resolveLanguage(
                outputLanguage = cfg.outputLanguage,
                audioLanguage = cfg.audioLanguage,
            )
            val resolvedTemplateId = BuiltinTemplateCatalog.resolveId(templateId)
            val dbTemplate = repository.getTemplate(resolvedTemplateId)
                ?: repository.getTemplate(templateId.orEmpty())
            val catalogEntry = BuiltinTemplateCatalog.get(resolvedTemplateId)
            val baseTemplate = dbTemplate?.system_prompt
                ?: catalogEntry?.systemPrompt
                ?: "Summarize into Overview, Key points, Action items."
            val localizedTemplate = SummaryLocalization.enhanceTemplatePrompt(
                templateId = resolvedTemplateId,
                basePrompt = baseTemplate,
                language = summaryLang,
            )
            val templatePrompt = buildString {
                append(localizedTemplate)
                if (personalization.isNotBlank()) {
                    append("\n\n").append(personalization)
                }
                if (prefs.customInstructions.isNotBlank()) {
                    append("\nCustom instructions: ").append(prefs.customInstructions)
                }
                if (prefs.contentFocus.isNotBlank()) {
                    append("\nContent focus: ").append(prefs.contentFocus)
                }
            }
            val templateOverride = run {
                val preferredModel = dbTemplate?.preferred_model
                    ?: catalogEntry?.preferredModel
                val preferredProfile = dbTemplate?.preferred_endpoint_profile_id
                    ?: catalogEntry?.preferredEndpointProfileId
                if (preferredModel.isNullOrBlank() && preferredProfile.isNullOrBlank()) {
                    null
                } else {
                    ApiModelOverride(
                        profileId = preferredProfile?.takeIf { it.isNotBlank() },
                        modelId = preferredModel?.takeIf { it.isNotBlank() },
                    )
                }
            }
            val summaryOverride = apiModelOverride ?: templateOverride
            val llm = aiRouter.llm()
            val llmModel = when (cfg.summaryMode) {
                ComputeMode.Local ->
                    cfg.localLlmModelId?.takeIf { it.isNotBlank() } ?: DEFAULT_LOCAL_LLM_MODEL_ID
                ComputeMode.Api -> ""
            }
            val summary = summarizeWithMapReduce(
                llmComplete = { req ->
                    llm.complete(
                        req.copy(
                            apiTask = AiApiTask.Summary,
                            apiOverride = summaryOverride,
                        ),
                        llmModel,
                    )
                },
                templatePrompt = templatePrompt,
                transcriptMarkdown = transcriptMarkdown,
                outputLanguage = summaryLang,
            )
            val templateName = dbTemplate?.name
                ?: catalogEntry?.nameEn
                ?: catalogEntry?.namePtBr
                ?: resolvedTemplateId
            repository.saveSummaryVersion(
                recordingId = recordingId,
                markdown = summary.text,
                templateId = resolvedTemplateId,
                templateName = templateName,
            )
            repository.updateStatus(recordingId, "ready")
            if (settings.preferences.value.deleteAudioAfterProcessing &&
                !localPath.isNullOrBlank()
            ) {
                emit(JobProgress.Running(0.95f, "Removing local audio"))
                deleteFile(localPath)
                repository.clearLocalAudioPath(recordingId)
            }
            val successMsg = buildString {
                append("Processed $recordingId")
                summary.warning?.let { append(" — ").append(it) }
            }
            emit(JobProgress.Succeeded(successMsg))
            } finally {
                pcmBuffer.close()
                runCatching { spillRoot.deleteRecursively() }
            }
        } catch (t: Throwable) {
            if (t is CancellationException ||
                t is kotlin.coroutines.cancellation.CancellationException
            ) {
                throw t
            }
            repository.updateStatus(recordingId, "failed")
            emit(JobProgress.Failed(t.message ?: t.toString()))
        }
    }

    private suspend fun summarizeWithMapReduce(
        llmComplete: suspend (LlmRequest) -> dev.velum.ai.engine.LlmResult,
        templatePrompt: String,
        transcriptMarkdown: String,
        outputLanguage: String,
    ): dev.velum.ai.engine.LlmResult {
        if (transcriptMarkdown.length <= MAP_REDUCE_CHAR_THRESHOLD) {
            return llmComplete(
                LlmRequest(
                    systemPrompt = templatePrompt,
                    userPrompt = transcriptMarkdown,
                    outputLanguage = outputLanguage,
                ),
            )
        }
        val chunks = chunkText(transcriptMarkdown, MAP_REDUCE_CHUNK_CHARS)
        val partials = chunks.mapIndexed { index, chunk ->
            llmComplete(
                LlmRequest(
                    systemPrompt = SummaryLocalization.mapReduceChunkInstruction(
                        language = outputLanguage,
                        index = index,
                        lastIndex = chunks.lastIndex,
                    ),
                    userPrompt = chunk,
                    outputLanguage = outputLanguage,
                    maxOutputTokens = 512,
                ),
            ).text
        }
        val reduced = llmComplete(
            LlmRequest(
                systemPrompt = templatePrompt,
                userPrompt = partials.joinToString("\n\n---\n\n"),
                outputLanguage = outputLanguage,
            ),
        )
        return reduced.copy(usedMapReduce = true)
    }

    private suspend fun ensureApiKeyIfNeeded(
        mode: ComputeMode,
        apiTask: AiApiTask,
        label: String,
    ) {
        if (mode != ComputeMode.Api) return
        val resolved = settings.resolveApiModel(apiTask)
        val key = settings.getProfileApiKey(resolved.profileId)
        if (key.isNullOrBlank()) {
            throw MissingApiKeyException(
                "API key required for $label. Add your OpenAI-compatible key in Settings → AI provider.",
            )
        }
    }

    private suspend fun awaitThermalGate(emit: suspend (JobProgress) -> Unit) {
        if (!thermal.isHot()) return
        emit(JobProgress.PausedThermal("Device thermal status severe/critical — pausing AI work"))
        thermal.awaitCool { message ->
            emit(JobProgress.PausedThermal(message))
        }
    }

    private suspend fun transcribeWindowed(
        asr: Transcriber,
        asrModel: String,
        filename: String,
        pcm: PcmAudioBuffer,
        sampleRate: Int,
        languageHint: String?,
        recordingId: String,
        initialPrompt: String?,
    ): TranscriptionResult {
        val total = pcm.sizeBytes.toInt().coerceAtLeast(0)
        val windows = WindowedAsr.windows(total, sampleRate)
        if (windows.size <= 1) {
            val bytes = pcm.asFullArrayOrNull() ?: pcm.slice(0, total)
            return asr.transcribe(
                AudioInput(
                    filename = filename,
                    bytes = bytes,
                    sampleRateHz = sampleRate,
                    languageHint = languageHint,
                    recordingId = recordingId,
                    initialPrompt = initialPrompt,
                ),
                asrModel,
            )
        }
        val parts = ArrayList<TranscriptionResult>(windows.size)
        val starts = ArrayList<Long>(windows.size)
        for (w in windows) {
            thermal.awaitCool { }
            val slice = pcm.slice(w.startByte, w.endByte)
            parts += asr.transcribe(
                AudioInput(
                    filename = filename,
                    bytes = slice,
                    sampleRateHz = sampleRate,
                    languageHint = languageHint,
                    recordingId = recordingId,
                    initialPrompt = if (parts.isEmpty()) initialPrompt else null,
                ),
                asrModel,
            )
            starts += w.startMs
        }
        return WindowedAsr.mergeWindowResults(parts, starts)
    }

    private fun looksLikeOgg(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 'O'.code.toByte() &&
            bytes[1] == 'g'.code.toByte() &&
            bytes[2] == 'g'.code.toByte() &&
            bytes[3] == 'S'.code.toByte()

    private fun looksLikeWav(bytes: ByteArray): Boolean =
        bytes.size >= 12 &&
            bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() &&
            bytes[9] == 'A'.code.toByte() &&
            bytes[10] == 'V'.code.toByte() &&
            bytes[11] == 'E'.code.toByte()

    private data class WavPcmRange(val start: Int, val endExclusive: Int, val sampleRateHz: Int)

    /** Locate PCM payload bounds without copying (for large-file spill). */
    private fun locateWavPcm(bytes: ByteArray): WavPcmRange {
        if (!looksLikeWav(bytes)) return WavPcmRange(0, bytes.size, 16_000)
        var offset = 12
        var sampleRate = 16_000
        var dataStart = -1
        var dataSize = 0
        while (offset + 8 <= bytes.size) {
            val id = fourCc(bytes, offset)
            val size = u32le(bytes, offset + 4)
            val body = offset + 8
            when (id) {
                "fmt " -> {
                    if (body + 16 <= bytes.size) {
                        sampleRate = u32le(bytes, body + 4)
                    }
                }
                "data" -> {
                    dataStart = body
                    dataSize = size.coerceAtMost(bytes.size - body)
                    break
                }
            }
            offset = body + size + (size % 2) // word-align
        }
        if (dataStart < 0) {
            val start = if (bytes.size > 44) 44 else bytes.size
            return WavPcmRange(start, bytes.size, sampleRate)
        }
        val end = (dataStart + dataSize).coerceAtMost(bytes.size)
        return WavPcmRange(dataStart, end, sampleRate)
    }

    private fun appendRangeToFile(
        bytes: ByteArray,
        start: Int,
        endExclusive: Int,
        dest: ModelStoreDir,
    ) {
        dest.writeBytes(ByteArray(0))
        var offset = start
        val chunk = 256 * 1024
        while (offset < endExclusive) {
            val end = (offset + chunk).coerceAtMost(endExclusive)
            dest.appendBytes(bytes.copyOfRange(offset, end))
            offset = end
        }
    }

    private fun u32le(bytes: ByteArray, at: Int): Int {
        if (at + 4 > bytes.size) return 0
        return (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or
            ((bytes[at + 3].toInt() and 0xFF) shl 24)
    }

    private fun fourCc(bytes: ByteArray, at: Int): String {
        if (at + 4 > bytes.size) return ""
        return buildString(4) {
            append(bytes[at].toInt().toChar())
            append(bytes[at + 1].toInt().toChar())
            append(bytes[at + 2].toInt().toChar())
            append(bytes[at + 3].toInt().toChar())
        }
    }

    companion object {
        /**
         * Legacy name kept for callers/tests: in-memory PCM budget before spill-to-disk.
         * Longer recordings spill to a temp file and are windowed — they no longer hard-fail.
         */
        const val MAX_AUDIO_BYTES: Long = PcmAudioBuffer.IN_MEMORY_BUDGET_BYTES

        /** Rough ~4k tokens; map-reduce when longer. */
        const val MAP_REDUCE_CHAR_THRESHOLD = 12_000
        const val MAP_REDUCE_CHUNK_CHARS = 8_000

        fun chunkText(text: String, chunkChars: Int): List<String> {
            if (text.length <= chunkChars) return listOf(text)
            val chunks = mutableListOf<String>()
            var start = 0
            while (start < text.length) {
                var end = (start + chunkChars).coerceAtMost(text.length)
                if (end < text.length) {
                    val nl = text.lastIndexOf('\n', end)
                    if (nl > start + chunkChars / 2) end = nl + 1
                }
                chunks += text.substring(start, end)
                start = end
            }
            return chunks
        }
    }
}
