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
                            AudioInput(filename, audioBytes, sampleRate, cfg.audioLanguage),
                            diarModel,
                        )
                    } catch (t: ModelNotReadyException) {
                        // Diarization optional when local model missing — keep ASR text.
                        null
                    } catch (t: UnsupportedOperationException) {
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
            val templatePrompt = templateId?.let { repository.getTemplate(it)?.system_prompt }
                ?: repository.getTemplate("tpl-meeting")?.system_prompt
                ?: "Summarize into Overview, Key points, Action items."
            val llm = aiRouter.llm()
            val llmModel = when (cfg.summaryMode) {
                ComputeMode.Local -> cfg.localLlmModelId.orEmpty()
                ComputeMode.Api -> cfg.chatModel.orEmpty()
            }
            val summary = llm.complete(
                LlmRequest(
                    systemPrompt = templatePrompt,
                    userPrompt = transcriptMarkdown,
                    outputLanguage = cfg.outputLanguage,
                ),
                llmModel,
            )
            repository.updateSummary(recordingId, summary.text)
            repository.updateStatus(recordingId, "ready")
            emit(JobProgress.Succeeded("Processed $recordingId"))
        } catch (t: Throwable) {
            repository.updateStatus(recordingId, "failed")
            emit(JobProgress.Failed(t.message ?: t.toString()))
        }
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
}
