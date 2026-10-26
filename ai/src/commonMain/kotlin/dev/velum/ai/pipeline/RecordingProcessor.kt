package dev.velum.ai.pipeline

import dev.velum.ai.MissingApiKeyException
import dev.velum.ai.audio.createOpusDecoder
import dev.velum.ai.engine.AiTask
import dev.velum.ai.engine.AiTaskRouter
import dev.velum.ai.engine.AudioInput
import dev.velum.ai.engine.DEFAULT_LOCAL_DIARIZATION_MODEL_ID
import dev.velum.ai.engine.DEFAULT_LOCAL_LLM_MODEL_ID
import dev.velum.ai.engine.JobProgress
import dev.velum.ai.engine.LlmRequest
import dev.velum.ai.engine.requiredLocalModelId
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
                } else if (filename.endsWith(".wav", ignoreCase = true) || looksLikeWav(raw)) {
                    val wav = stripWavToPcm(raw)
                    audioBytes = wav.pcm
                    sampleRate = wav.sampleRateHz
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
                        ComputeMode.Local -> requiredLocalModelId(AiTask.Transcription, cfg)
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
                        ComputeMode.Local ->
                            cfg.localDiarizationModelId?.takeIf { it.isNotBlank() }
                                ?: DEFAULT_LOCAL_DIARIZATION_MODEL_ID
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
                ComputeMode.Local ->
                    cfg.localLlmModelId?.takeIf { it.isNotBlank() } ?: DEFAULT_LOCAL_LLM_MODEL_ID
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

    private data class WavPcm(val pcm: ByteArray, val sampleRateHz: Int)

    /** Minimal PCM WAV reader (fmt + data chunks). */
    private fun stripWavToPcm(bytes: ByteArray): WavPcm {
        if (!looksLikeWav(bytes)) return WavPcm(bytes, 16_000)
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
            // Classic 44-byte header fallback
            val pcm = if (bytes.size > 44) bytes.copyOfRange(44, bytes.size) else ByteArray(0)
            return WavPcm(pcm, sampleRate)
        }
        val end = (dataStart + dataSize).coerceAtMost(bytes.size)
        return WavPcm(bytes.copyOfRange(dataStart, end), sampleRate)
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
