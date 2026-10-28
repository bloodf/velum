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
                filename = localPath.substringAfterLast('/')
                spillRoot.createDirectories()
                val pcmFile = spillRoot.resolve("pcm.s16le")
                val oggByExt = filename.endsWith(".ogg", ignoreCase = true) ||
                    filename.endsWith(".opus", ignoreCase = true)
                if (oggByExt) {
                    // Stream Ogg pages from disk — never hold full Ogg or PCM on the heap.
                    val meta = createOpusDecoder().decodePathToPcmFile(localPath, pcmFile)
                    sampleRate = meta.sampleRateHz
                    pcmBuffer = PcmAudioBuffer.fromExistingFile(pcmFile, meta.sizeBytes)
                } else if (filename.endsWith(".wav", ignoreCase = true)) {
                    val wavMeta = streamWavPathToPcmFile(localPath, pcmFile)
                    sampleRate = wavMeta.sampleRateHz
                    pcmBuffer = PcmAudioBuffer.fromExistingFile(pcmFile, wavMeta.sizeBytes)
                } else {
                    // Extension-less / odd names: sniff ≤256 KiB, then stream — never readFileBytes.
                    val src = ModelStoreDir(localPath)
                    val fileLen = src.length()
                    val probeLen = fileLen.coerceAtMost(CONTENT_SNIFF_MAX_BYTES.toLong()).toInt()
                    val probe = if (probeLen > 0) src.readRange(0, probeLen) else ByteArray(0)
                    when {
                        looksLikeOgg(probe) -> {
                            val meta = createOpusDecoder().decodePathToPcmFile(localPath, pcmFile)
                            sampleRate = meta.sampleRateHz
                            pcmBuffer = PcmAudioBuffer.fromExistingFile(pcmFile, meta.sizeBytes)
                        }
                        looksLikeWav(probe) -> {
                            val wavMeta = streamWavPathToPcmFile(localPath, pcmFile)
                            sampleRate = wavMeta.sampleRateHz
                            pcmBuffer = PcmAudioBuffer.fromExistingFile(pcmFile, wavMeta.sizeBytes)
                        }
                        else -> throw IllegalArgumentException(
                            "Unsupported audio format (expected Ogg Opus or WAV): $localPath",
                        )
                    }
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
                    ensureApiKeyIfNeeded(
                        cfg.transcriptionMode,
                        AiApiTask.Transcription,
                        "transcription",
                        apiModelOverride,
                    )
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
                        apiOverride = apiModelOverride,
                    )

                    emit(JobProgress.Running(0.45f, "Diarizing"))
                    awaitThermalGate { emit(it) }
                    ensureApiKeyIfNeeded(
                        cfg.diarizationMode,
                        AiApiTask.Diarization,
                        "diarization",
                        apiModelOverride,
                    )
                    val diar = aiRouter.diarizer()
                    val diarModel = when (cfg.diarizationMode) {
                        ComputeMode.Local ->
                            cfg.localDiarizationModelId?.takeIf { it.isNotBlank() }
                                ?: DEFAULT_LOCAL_DIARIZATION_MODEL_ID
                        ComputeMode.Api ->
                            settings.resolveApiModel(AiApiTask.Diarization, apiModelOverride).modelId
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
            // Field-wise merge: AutoFlow profile-only override must not drop template model.
            val summaryOverride = mergeApiOverrides(apiModelOverride, templateOverride)
            ensureApiKeyIfNeeded(
                cfg.summaryMode,
                AiApiTask.Summary,
                "summaries",
                summaryOverride,
            )
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
        apiModelOverride: ApiModelOverride? = null,
    ) {
        if (mode != ComputeMode.Api) return
        val resolved = settings.resolveApiModel(apiTask, apiModelOverride)
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
        apiOverride: ApiModelOverride? = null,
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
                    apiOverride = apiOverride,
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
                    apiOverride = apiOverride,
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

    private data class WavPcmHeader(
        val dataStart: Int,
        /** Declared data chunk size from the header (-1 if unknown). */
        val dataSize: Int,
        val sampleRateHz: Int,
    )

    private data class WavStreamMeta(val sampleRateHz: Int, val sizeBytes: Long)

    /** Stream WAV from disk — probe header only (≤256 KiB, widen once if needed), copy PCM slices. */
    private fun streamWavPathToPcmFile(localPath: String, pcmFile: ModelStoreDir): WavStreamMeta {
        val src = ModelStoreDir(localPath)
        val fileLen = src.length()
        val probeLen = fileLen.coerceAtMost(CONTENT_SNIFF_MAX_BYTES.toLong()).toInt()
        val probe = if (probeLen > 0) src.readRange(0, probeLen) else ByteArray(0)
        require(looksLikeWav(probe)) { "Not a WAV file: $localPath" }
        var header = locateWavPcmHeader(probe, fileLen)
        if (header.dataStart < 0 && fileLen > probeLen) {
            val wideLen = fileLen.coerceAtMost(2L * 1024 * 1024).toInt()
            header = locateWavPcmHeader(src.readRange(0, wideLen), fileLen)
        }
        val pcmStart = if (header.dataStart >= 0) header.dataStart.toLong() else 44L
        val pcmLen = if (header.dataSize >= 0) {
            header.dataSize.toLong().coerceAtMost(fileLen - pcmStart)
        } else {
            (fileLen - pcmStart).coerceAtLeast(0L)
        }
        copyFileRangeToPcm(src, pcmStart, pcmLen, pcmFile)
        return WavStreamMeta(sampleRateHz = header.sampleRateHz, sizeBytes = pcmLen)
    }

    /**
     * Parse WAV fmt/data from a header probe. Does **not** clamp dataSize to the probe length —
     * callers stream the full payload from disk.
     */
    private fun locateWavPcmHeader(probe: ByteArray, fileLength: Long): WavPcmHeader {
        if (!looksLikeWav(probe)) return WavPcmHeader(-1, -1, 16_000)
        var offset = 12
        var sampleRate = 16_000
        while (offset + 8 <= probe.size) {
            val id = fourCc(probe, offset)
            val size = u32le(probe, offset + 4)
            val body = offset + 8
            when (id) {
                "fmt " -> {
                    if (body + 16 <= probe.size) {
                        sampleRate = u32le(probe, body + 4)
                    }
                }
                "data" -> {
                    val maxSize = (fileLength - body).coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong())
                    return WavPcmHeader(
                        dataStart = body,
                        dataSize = size.toLong().coerceAtMost(maxSize).toInt(),
                        sampleRateHz = sampleRate,
                    )
                }
            }
            offset = body + size + (size % 2)
            if (offset < 0) break // overflow guard
        }
        return WavPcmHeader(-1, -1, sampleRate)
    }

    /** Copy [length] bytes from [src] starting at [start] into [dest] in 256 KiB slices. */
    private fun copyFileRangeToPcm(
        src: ModelStoreDir,
        start: Long,
        length: Long,
        dest: ModelStoreDir,
    ) {
        streamCopyRange(
            start = start,
            length = length,
            chunkBytes = STREAM_COPY_CHUNK_BYTES,
            readAt = { offset, n -> src.readRange(offset, n) },
            writeAll = { dest.writeBytes(it) },
            append = { dest.appendBytes(it) },
        )
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

        /** Max bytes read to sniff OggS / RIFF-WAVE when the path has no known extension. */
        const val CONTENT_SNIFF_MAX_BYTES: Int = 256 * 1024

        /** Max bytes per read when streaming WAV PCM from disk to the spill file. */
        const val STREAM_COPY_CHUNK_BYTES: Int = 256 * 1024

        /** Rough ~4k tokens; map-reduce when longer. */
        const val MAP_REDUCE_CHAR_THRESHOLD = 12_000
        const val MAP_REDUCE_CHUNK_CHARS = 8_000

        /**
         * Copy [length] bytes starting at [start] via [readAt] / [append] in ≤[chunkBytes] slices.
         * Used by WAV import and by JVM tests with an instrumented reader.
         */
        internal fun streamCopyRange(
            start: Long,
            length: Long,
            chunkBytes: Int = STREAM_COPY_CHUNK_BYTES,
            readAt: (offset: Long, length: Int) -> ByteArray,
            writeAll: (ByteArray) -> Unit,
            append: (ByteArray) -> Unit,
        ) {
            writeAll(ByteArray(0))
            if (length <= 0L) return
            var offset = start
            val end = start + length
            while (offset < end) {
                val n = ((end - offset).coerceAtMost(chunkBytes.toLong())).toInt()
                append(readAt(offset, n))
                offset += n
            }
        }

        /** Prefer primary fields; fill blanks from secondary (template defaults). */
        internal fun mergeApiOverrides(
            primary: ApiModelOverride?,
            secondary: ApiModelOverride?,
        ): ApiModelOverride? {
            if (primary == null) return secondary
            if (secondary == null) return primary
            return ApiModelOverride(
                profileId = primary.profileId?.takeIf { it.isNotBlank() }
                    ?: secondary.profileId?.takeIf { it.isNotBlank() },
                modelId = primary.modelId?.takeIf { it.isNotBlank() }
                    ?: secondary.modelId?.takeIf { it.isNotBlank() },
            )
        }

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
