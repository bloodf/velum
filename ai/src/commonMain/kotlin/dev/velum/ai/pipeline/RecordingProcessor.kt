package dev.velum.ai.pipeline

import dev.velum.ai.ApiErrorSanitizer
import dev.velum.ai.MissingApiKeyException
import dev.velum.ai.AiApiException
import dev.velum.ai.audio.OpusDecoder
import dev.velum.ai.audio.createOpusDecoder
import dev.velum.ai.engine.AiTask
import dev.velum.ai.engine.AiTaskRouter
import dev.velum.ai.engine.AudioInput
import dev.velum.ai.engine.DEFAULT_LOCAL_DIARIZATION_MODEL_ID
import dev.velum.ai.engine.JobProgress
import dev.velum.ai.engine.Transcriber
import dev.velum.ai.engine.TranscriptionResult
import dev.velum.ai.engine.LlmRequest
import dev.velum.ai.engine.requiredLocalAsrModelId
import dev.velum.ai.local.ModelNotReadyException
import dev.velum.ai.local.ModelStoreDir
import dev.velum.ai.local.defaultModelStoreRoot
import dev.velum.ai.local.walkFiles
import dev.velum.core.data.VelumRepository
import dev.velum.core.model.ProcessingWarning
import dev.velum.core.model.ComputeMode
import dev.velum.core.settings.AiApiTask
import dev.velum.core.settings.ApiModelOverride
import dev.velum.core.settings.SettingsRepository
import dev.velum.core.templates.BuiltinTemplateCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/** Progress message when the Ogg source ended mid-page; only the decodable part is processed. */
const val TRUNCATED_AUDIO_MESSAGE = "Audio file is truncated; processing the part that could be decoded"
/** Bytes read to tell an Ogg or WAV file apart when the file name does not say. */
private const val SNIFF_BYTES = 16

const val INSUFFICIENT_STORAGE_MESSAGE =
    "Not enough free storage to process this recording. Free up space and try again."

/** True when [t] or a cause reports a full disk (ENOSPC); message text is all common code can see. */
internal fun isOutOfStorage(t: Throwable): Boolean {
    val chain = generateSequence(t) { it.cause }.take(8).toList()
    // AiApiException text comes from a remote provider, not this device's disk; wrappers copy it
    // into their own message (RuntimeException(cause)), so any provider error in the chain rules it out.
    if (chain.any { it is AiApiException }) return false
    return chain.any { e ->
        val m = e.message.orEmpty()
        m.contains("ENOSPC") || m.contains("No space left", ignoreCase = true) || m.contains("disk is full", ignoreCase = true)
    }
}

/** A spill dir untouched for this long belongs to no live job (a decode keeps appending to its file). */
const val STALE_PCM_SPILL_AGE_MS: Long = 24L * 60L * 60L * 1000L

/**
 * Removes spilled PCM dirs (`tmp/pcm-*`) left by a process death mid-decode, but only when nothing inside them
 * (nor the dir itself) changed since [olderThanEpochMs]. A job still decoding keeps modifying its file, so it is
 * kept. Returns how many were removed. Android calls this at startup; desktop has no caller yet.
 */
fun sweepOrphanPcmSpill(
    olderThanEpochMs: Long,
    root: ModelStoreDir = defaultModelStoreRoot(),
): Int =
    root.resolve("tmp").takeIf { it.isDirectory() }?.listChildren().orEmpty()
        .filter { it.name.startsWith("pcm-") }
        .filter { newestChangeMillis(it) < olderThanEpochMs }
        .count { runCatching { it.deleteRecursively() }; !it.exists() }

private fun newestChangeMillis(dir: ModelStoreDir): Long =
    maxOf(dir.lastModifiedMillis(), dir.walkFiles().maxOfOrNull { it.lastModifiedMillis() } ?: 0L)

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
    private val opusDecoder: () -> OpusDecoder = ::createOpusDecoder,
) {
    private val nextRun = MutableStateFlow(0L)
    private val activeRuns = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val statusLock = Mutex()

    fun process(
        recordingId: String,
        templateId: String? = null,
        retranscribe: Boolean = true,
        apiModelOverride: ApiModelOverride? = null,
    ): Flow<JobProgress> = flow {
        emit(JobProgress.Running(0.05f, "Starting"))
        val priorStatus = repository.getRecording(recordingId)?.status
        // Per-run token: only the newest run of a recording may restore its status when cancelled (see the handler).
        val run = nextRun.updateAndGet { it + 1 }

        try {
            // Token and "generating" are published together, under the lock every status write of a run takes.
            statusLock.withLock {
                activeRuns.update { it + (recordingId to run) }
                repository.setProcessingError(recordingId, null)
                repository.updateStatus(recordingId, "generating")
            }
            val recording = repository.getRecording(recordingId)
                ?: throw IllegalArgumentException("Recording not found: $recordingId")
            val cfg = settings.settings.value

            val filename: String
            val sampleRate: Int
            val localPath = recording.local_path
            val job = currentCoroutineContext()[Job]
            val spillRoot = ModelStoreDir(defaultModelStoreRoot().absolutePath)
                .resolve("tmp")
                .resolve("pcm-$recordingId-$run-${kotlin.random.Random.nextLong().toString(16)}")
            var pcmBuffer: PcmAudioBuffer = PcmAudioBuffer.Memory(ByteArray(0))
            var truncatedAudio = false
            try {
            // Summary-only runs (change template) use the stored transcript; audio is not needed.
            if (retranscribe && !localPath.isNullOrBlank()) {
                emit(JobProgress.Running(0.1f, "Decoding audio"))
                awaitThermalGate { emit(it) }
                filename = localPath.substringAfterLast('/')
                spillRoot.createDirectories()
                val pcmFile = spillRoot.resolve("pcm.s16le")
                val oggByExt = filename.endsWith(".ogg", ignoreCase = true) ||
                    filename.endsWith(".opus", ignoreCase = true)
                val wavByExt = filename.endsWith(".wav", ignoreCase = true)
                val src = ModelStoreDir(localPath)
                val fileLen = src.length()
                // An unknown extension is sniffed from its first bytes, never read whole: the container decides.
                val sniffed = if (oggByExt || wavByExt) {
                    ByteArray(0)
                } else {
                    src.readRange(0, fileLen.coerceAtMost(SNIFF_BYTES.toLong()).toInt())
                }
                val isOgg = oggByExt || (!wavByExt && looksLikeOgg(sniffed))
                val isWav = !isOgg && (wavByExt || looksLikeWav(sniffed))
                if (isOgg) {
                    // Stream Ogg pages from disk — never hold full Ogg or PCM on the heap.
                    val meta = opusDecoder().decodePathToPcmFile(localPath, pcmFile) { job?.ensureActive() }
                    sampleRate = meta.sampleRateHz
                    pcmBuffer = PcmAudioBuffer.fromExistingFile(pcmFile, meta.sizeBytes)
                    if (meta.truncated) {
                        truncatedAudio = true
                        emit(JobProgress.Running(0.2f, TRUNCATED_AUDIO_MESSAGE))
                    }
                } else if (isWav) {
                    // Stream WAV from disk — probe header only, then copy PCM slices.
                    val probeLen = fileLen.coerceAtMost(256L * 1024L).toInt()
                    val probe = if (probeLen > 0) src.readRange(0, probeLen) else ByteArray(0)
                    require(looksLikeWav(probe)) { "Not a WAV file: $localPath" }
                    var header = locateWavPcmHeader(probe, fileLen)
                    if (header.dataStart < 0 && fileLen > probeLen) {
                        val wideLen = fileLen.coerceAtMost(2L * 1024 * 1024).toInt()
                        header = locateWavPcmHeader(src.readRange(0, wideLen), fileLen)
                    }
                    sampleRate = header.sampleRateHz
                    val pcmStart = if (header.dataStart >= 0) header.dataStart.toLong() else 44L
                    val pcmLen = if (header.dataSize >= 0) {
                        header.dataSize.toLong().coerceAtMost(fileLen - pcmStart)
                    } else {
                        (fileLen - pcmStart).coerceAtLeast(0L)
                    }
                    requireSpillable(pcmLen) // before copying: a too-large file must not fill the disk first
                    copyFileRangeToPcm(src, pcmStart, pcmLen, pcmFile) { job?.ensureActive() }
                    pcmBuffer = PcmAudioBuffer.fromExistingFile(pcmFile, pcmLen)
                } else {
                    // Not Ogg or WAV. A known container that is not supported here must not be read as PCM: that
                    // would give a noise transcript with no error. Anything else is raw 16 kHz mono PCM.
                    unsupportedContainer(sniffed)?.let { throw IllegalArgumentException(unsupportedAudioMessage(it)) }
                    sampleRate = 16_000
                    requireSpillable(fileLen)
                    copyFileRangeToPcm(src, 0L, fileLen, pcmFile) { job?.ensureActive() }
                    pcmBuffer = PcmAudioBuffer.fromExistingFile(pcmFile, fileLen)
                }
                // Persist (or clear) the stable warning code so the UI can show it after the job ends.
                // A device CRC mismatch recorded at download time cannot be rederived, so it is never cleared here.
                // Re-read: the row may have changed since the snapshot taken when the job started.
                val currentWarning = repository.getRecording(recordingId)?.processing_warning
                val warning = when {
                    currentWarning == ProcessingWarning.DEVICE_CRC_MISMATCH -> ProcessingWarning.DEVICE_CRC_MISMATCH
                    truncatedAudio -> ProcessingWarning.TRUNCATED_AUDIO
                    else -> null
                }
                repository.setProcessingWarning(recordingId, warning)
            } else {
                // Demo / seed rows have no audio file — keep existing transcript path for summary-only.
                filename = "missing.ogg"
                sampleRate = 16_000
            }

            val prefs = settings.preferences.value
            val personalization = repository.personalizationPromptFragment(
                includeMemory = prefs.useSavedMemory,
            )

            val (storedTranscript, storedMarkers) = TranscriptFormatter.splitMarkers(recording.transcript.orEmpty())
            var transcriptMarkdown = storedTranscript
            if (!retranscribe && transcriptMarkdown.isBlank()) {
                throw IllegalStateException("This recording has no transcript to summarize.")
            }
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
                        ComputeMode.Local -> requiredLocalAsrModelId(cfg)
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
                    repository.updateTranscript(recordingId, listOfNotNull(transcriptMarkdown, storedMarkers).joinToString("\n\n"))

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
            // Exact row first: an edited builtin keeps its id (builtin = 0) and must win over
            // legacy / removed-duplicate alias resolution (#99).
            val exactTemplate = templateId?.takeIf { it.isNotBlank() }?.let { repository.getTemplate(it) }
            val resolvedTemplateId = effectiveTemplateId(exactTemplate?.id, templateId)
            val dbTemplate = exactTemplate ?: repository.getTemplate(resolvedTemplateId)
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
                // Blank lets the engine resolve it, as Ask AI does.
                ComputeMode.Local -> cfg.localLlmModelId.orEmpty()
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
            val deleteAudio = settings.preferences.value.deleteAudioAfterProcessing && !localPath.isNullOrBlank()
            // The summary, status and audio path are written together and only by the newest run. A replaced run
            // saves nothing: its version would otherwise become the active one over the newer run's.
            val newest = statusLock.withLock {
                (activeRuns.value[recordingId] == run).also {
                    if (it) {
                        repository.saveSummaryVersion(
                            recordingId = recordingId,
                            markdown = summary.text,
                            templateId = resolvedTemplateId,
                            templateName = templateName,
                        )
                        repository.updateStatus(recordingId, "ready")
                        repository.setProcessingError(recordingId, null)
                        if (deleteAudio) repository.clearLocalAudioPath(recordingId)
                        // Finished: a cancel from here on must not restore an earlier status over "ready".
                        activeRuns.update { map -> map - recordingId }
                    }
                }
            }
            if (newest) {
                // The file goes outside the lock; the row no longer points at it.
                if (deleteAudio) {
                    // Delete first: emit is a cancellation point, and a collector that stops here must not leave
                    // the file behind after the row stopped pointing at it.
                    deleteFile(localPath!!)
                    emit(JobProgress.Running(0.95f, "Removed local audio"))
                }
                val successMsg = buildString {
                    append("Processed $recordingId")
                    summary.warning?.let { append(" — ").append(it) }
                }
                emit(JobProgress.Succeeded(successMsg))
            }
            } finally {
                pcmBuffer.close()
                runCatching { spillRoot.deleteRecursively() }
            }
        } catch (t: Throwable) {
            if (t is CancellationException ||
                t is kotlin.coroutines.cancellation.CancellationException
            ) {
                // Cancelled work saved no version; restore the status so notes stay usable (newest run only).
                withContext(NonCancellable) {
                    // A replacement run (AutoFlow's REPLACE) may start before this one unwinds and already own the
                    // row's "generating"; restoring here would write a false "failed" over it. Check and write are one
                    // step under the lock, so the replacement cannot publish between them.
                    statusLock.withLock {
                        if (activeRuns.value[recordingId] != run) return@withLock
                        activeRuns.update { it - recordingId }
                        val current = repository.getRecording(recordingId)
                        // This job set "generating" when it started. "queued" now means a replacing enqueue (AutoFlow's
                        // REPLACE) already took over the row; writing here would race its status and hand its runner
                        // a false terminal "failed".
                        if (current?.status == "queued") return@withLock
                        // A pre-run "generating"/"queued" state is not restorable: it would look stuck forever.
                        val restore = when (priorStatus) {
                            null, "generating", "queued" ->
                                if (current?.summary_markdown.isNullOrBlank()) "failed" else "ready"
                            else -> priorStatus
                        }
                        repository.updateStatus(recordingId, restore)
                    }
                }
                throw t
            }
            // The previous summary and versions are untouched; surface the reason in the UI.
            val reason = if (isOutOfStorage(t)) INSUFFICIENT_STORAGE_MESSAGE else t.message ?: t.toString()
            val newest = statusLock.withLock {
                (activeRuns.value[recordingId] == run).also {
                    if (it) {
                        repository.setProcessingError(recordingId, ApiErrorSanitizer.sanitize(reason))
                        repository.updateStatus(recordingId, "failed")
                    }
                }
            }
            // A replaced run reports nothing: the newer run owns the outcome and its notification.
            if (newest) emit(JobProgress.Failed(reason))
        } finally {
            // Only this run's own token; a newer run's must survive.
            activeRuns.update { if (it[recordingId] == run) it - recordingId else it }
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

    /**
     * Parse WAV fmt/data from a header probe. Does **not** clamp
     * dataSize to the probe length — callers stream the full payload from disk.
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

    private fun requireSpillable(pcmBytes: Long) =
        require(pcmBytes <= PcmAudioBuffer.MAX_SPILL_BYTES) {
            "Audio is too large to process: $pcmBytes bytes exceeds ${PcmAudioBuffer.MAX_SPILL_BYTES}"
        }

    /** Copy [length] bytes from [src] starting at [start] into [dest] in 256 KiB slices. */
    private fun copyFileRangeToPcm(
        src: ModelStoreDir,
        start: Long,
        length: Long,
        dest: ModelStoreDir,
        checkCancelled: () -> Unit,
    ) {
        dest.writeBytes(ByteArray(0))
        if (length <= 0L) return
        var offset = start
        val end = start + length
        val chunk = 256 * 1024
        while (offset < end) {
            checkCancelled()
            val n = ((end - offset).coerceAtMost(chunk.toLong())).toInt()
            dest.appendBytes(src.readRange(offset, n))
            offset += n
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

/** Plain error for audio whose container is recognised but not decoded by this path. */
internal fun unsupportedAudioMessage(container: String) =
    "Unsupported audio format ($container). This file is not Ogg Opus, WAV or raw 16 kHz PCM, so it was not transcribed; " +
        "re-download it from the device or convert it to WAV or Ogg Opus."

/**
 * Names a well-known audio container from its first bytes, or null. Only signatures that cannot start raw PCM by
 * accident are used. A bare MP3 or ADTS frame sync is NOT one: `FF FB` is the sample -1025 and `FF F1` is -3585, so
 * header-less MP3 or AAC streams stay as raw PCM here (as before), and only ID3-tagged MP3 is recognised.
 */
internal fun unsupportedContainer(head: ByteArray): String? {
    fun at(i: Int, text: String) = head.size >= i + text.length && text.indices.all { head[i + it] == text[it].code.toByte() }
    fun b(i: Int) = if (head.size > i) head[i].toInt() and 0xFF else -1
    return when {
        at(0, "ID3") -> "MP3"
        at(4, "ftyp") -> "MP4/M4A"
        at(0, "fLaC") -> "FLAC"
        at(0, "#!AMR") -> "AMR"
        at(0, "RIFF") -> "RIFF (not WAVE)"
        head.size >= 4 && b(0) == 0x1A && b(1) == 0x45 && b(2) == 0xDF && b(3) == 0xA3 -> "WebM/Matroska"
        else -> null
    }
}
