package dev.velum.ai.pipeline

import dev.velum.ai.MissingApiKeyException
import dev.velum.ai.audio.createOpusDecoder
import dev.velum.ai.engine.AiTaskRouter
import dev.velum.ai.engine.AudioInput
import dev.velum.ai.engine.JobProgress
import dev.velum.ai.engine.LlmRequest
import dev.velum.ai.local.ModelNotReadyException
import dev.velum.ai.local.ModelStoreDir
import dev.velum.core.data.VelumRepository
import dev.velum.core.model.ComputeMode
import dev.velum.core.settings.SettingsRepository
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
) {
    fun process(
        recordingId: String,
        templateId: String? = null,
        retranscribe: Boolean = true,
    ): Flow<JobProgress> = flow {
        emit(JobProgress.Running(0.05f, "Starting"))
        repository.updateStatus(recordingId, "generating")

        try {
            val recording = repository.getRecording(recordingId)
                ?: throw IllegalArgumentException("Recording not found: $recordingId")
            val cfg = settings.settings.value

            val audioBytes: ByteArray
            val filename: String
            val sampleRate: Int
            val localPath = recording.local_path
            if (!localPath.isNullOrBlank()) {
                emit(JobProgress.Running(0.1f, "Decoding audio"))
                val raw = readFileBytes(localPath)
                filename = localPath.substringAfterLast('/')
                if (filename.endsWith(".ogg", ignoreCase = true) ||
                    filename.endsWith(".opus", ignoreCase = true) ||
                    looksLikeOgg(raw)
                ) {
                    val pcm = createOpusDecoder().decodeToPcm(raw)
                    audioBytes = pcm.toByteArray()
                    sampleRate = pcm.sampleRateHz
                } else {
                    audioBytes = raw
                    sampleRate = 16_000
                }
            } else {
                // Demo / seed rows have no audio file — keep existing transcript path for summary-only.
                audioBytes = ByteArray(0)
                filename = "missing.ogg"
                sampleRate = 16_000
            }

            val prefs = settings.preferences.value
            val personalization = repository.personalizationPromptFragment(
                includeMemory = prefs.useSavedMemory,
            )

            var transcriptMarkdown = recording.transcript.orEmpty()
            if (retranscribe) {
                if (audioBytes.isEmpty()) {
                    if (transcriptMarkdown.isBlank()) {
                        throw IllegalStateException(
                            "Recording has no local audio path and no existing transcript.",
                        )
                    }
                    emit(JobProgress.Running(0.35f, "Using existing transcript (no audio file)"))
                } else {
                    emit(JobProgress.Running(0.25f, "Transcribing"))
                    ensureApiKeyIfNeeded(cfg.transcriptionMode, "transcription")
                    val asr = aiRouter.transcriber()
                    val asrModel = when (cfg.transcriptionMode) {
                        ComputeMode.Local -> cfg.localAsrModelId.orEmpty()
                        ComputeMode.Api -> cfg.transcriptionModel.orEmpty()
                    }
                    val transcription = asr.transcribe(
                        AudioInput(
                            filename = filename,
                            bytes = audioBytes,
                            sampleRateHz = sampleRate,
                            languageHint = cfg.audioLanguage,
                            recordingId = recordingId,
                            initialPrompt = personalization.takeIf { it.isNotBlank() },
                        ),
                        asrModel,
                    )

                    emit(JobProgress.Running(0.45f, "Diarizing"))
                    ensureApiKeyIfNeeded(cfg.diarizationMode, "diarization")
                    val diar = aiRouter.diarizer()
                    val diarModel = when (cfg.diarizationMode) {
                        ComputeMode.Local -> cfg.localDiarizationModelId.orEmpty()
                        ComputeMode.Api -> ""
                    }
                    val diarization = try {
                        diar.diarize(
                            AudioInput(
                                filename = filename,
                                bytes = audioBytes,
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
            ensureApiKeyIfNeeded(cfg.summaryMode, "summaries")
            val baseTemplate = templateId?.let { repository.getTemplate(it)?.system_prompt }
                ?: repository.getTemplate("tpl-meeting")?.system_prompt
                ?: "Summarize into Overview, Key points, Action items."
            val templatePrompt = buildString {
                append(baseTemplate)
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
            val llm = aiRouter.llm()
            val llmModel = when (cfg.summaryMode) {
                ComputeMode.Local -> cfg.localLlmModelId.orEmpty()
                ComputeMode.Api -> cfg.chatModel.orEmpty()
            }
            val summary = summarizeWithMapReduce(
                llmComplete = { req -> llm.complete(req, llmModel) },
                templatePrompt = templatePrompt,
                transcriptMarkdown = transcriptMarkdown,
                outputLanguage = cfg.outputLanguage,
            )
            repository.updateSummary(recordingId, summary.text)
            repository.updateStatus(recordingId, "ready")
            emit(JobProgress.Succeeded("Processed $recordingId"))
        } catch (t: Throwable) {
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
                    systemPrompt = "Summarize this transcript section ($index/${chunks.lastIndex}) " +
                        "into concise bullets. Keep speaker names and action items.",
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

    private suspend fun ensureApiKeyIfNeeded(mode: ComputeMode, task: String) {
        if (mode != ComputeMode.Api) return
        val key = settings.getApiKey()
        if (key.isNullOrBlank()) {
            throw MissingApiKeyException(
                "API key required for $task. Add your OpenAI-compatible key in Account settings.",
            )
        }
    }

    private fun looksLikeOgg(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 'O'.code.toByte() &&
            bytes[1] == 'g'.code.toByte() &&
            bytes[2] == 'g'.code.toByte() &&
            bytes[3] == 'S'.code.toByte()

    companion object {
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
