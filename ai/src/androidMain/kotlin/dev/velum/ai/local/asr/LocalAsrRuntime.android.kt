package dev.velum.ai.local.asr

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import dev.velum.ai.engine.DiarizationResult
import dev.velum.ai.engine.LlmRequest
import dev.velum.ai.engine.LlmResult
import dev.velum.ai.engine.SpeakerSpan
import dev.velum.ai.engine.TranscriptSegment
import dev.velum.ai.engine.TranscriptionResult
import dev.velum.ai.local.VoiceProfileStore
import dev.velum.ai.local.pcm16LeToFloats
import dev.velum.ai.local.sliceFloats
import java.io.File

/**
 * Android sherpa-onnx OfflineRecognizer + OfflineSpeakerDiarization + LiteRT-LM.
 * Never returns placeholder transcript / summary text.
 */
actual class LocalAsrRuntime actual constructor() {
    actual suspend fun transcribe(
        pcmOrEncoded: ByteArray,
        sampleRateHz: Int,
        languageHint: String?,
        modelId: String,
        modelPath: String,
        initialPrompt: String?,
    ): TranscriptionResult {
        // Sherpa OfflineRecognizer has no Whisper initial_prompt; glossary is applied
        // via RecordingProcessor LLM prompts. Keep the param for API parity / future online models.
        @Suppress("UNUSED_PARAMETER")
        val ignoredPrompt = initialPrompt
        val modelDir = File(modelPath)
        if (!modelDir.exists()) {
            throw IllegalStateException(
                "ASR model directory missing at $modelPath. " +
                    "Download and extract the model in Model manager.",
            )
        }
        if (modelDir.isFile && isArchiveName(modelDir.name)) {
            throw IllegalStateException(
                "Model archive at $modelPath is not extracted. " +
                    "Extract sherpa-onnx weights (encoder/decoder/tokens) before Local ASR.",
            )
        }
        val config = buildRecognizerConfig(modelId, modelDir, languageHint)
            ?: throw IllegalStateException(
                missingAsrConfigMessage(modelId, modelDir),
            )
        val samples = pcm16LeToFloats(pcmOrEncoded)
        val recognizer = OfflineRecognizer(/* assetManager = */ null, config)
        try {
            val stream = recognizer.createStream()
            try {
                stream.acceptWaveform(samples, sampleRateHz)
                recognizer.decode(stream)
                val result = recognizer.getResult(stream)
                val text = result.text.trim()
                if (text.isEmpty()) {
                    throw IllegalStateException(
                        "Sherpa OfflineRecognizer returned empty text for modelId=$modelId. " +
                            "Check audio sample rate ($sampleRateHz) and model files under $modelPath.",
                    )
                }
                val segments = timestampsToSegments(text, result.tokens, result.timestamps)
                return TranscriptionResult(
                    text = text,
                    segments = segments,
                    detectedLanguage = result.lang.takeIf { it.isNotBlank() } ?: languageHint,
                    engineId = "local-asr",
                    modelId = modelId,
                )
            } finally {
                stream.release()
            }
        } finally {
            recognizer.release()
        }
    }

    actual suspend fun diarize(
        pcmOrEncoded: ByteArray,
        sampleRateHz: Int,
        modelId: String,
        modelPath: String,
        embeddingModelPath: String,
        voiceProfiles: VoiceProfileStore?,
        recordingId: String?,
    ): DiarizationResult {
        val segModel = findOnnx(File(modelPath))
            ?: throw IllegalStateException(
                "Diarization segmentation ONNX missing under $modelPath. " +
                    "Expected a pyannote *.onnx after archive extract.",
            )
        val embModel = findOnnx(File(embeddingModelPath))
            ?: throw IllegalStateException(
                "Speaker embedding ONNX missing under $embeddingModelPath. " +
                    "Download WeSpeaker or TitaNet in Model manager.",
            )
        val samples = pcm16LeToFloats(pcmOrEncoded)
        val config = OfflineSpeakerDiarizationConfig(
            segmentation = OfflineSpeakerSegmentationModelConfig(
                pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(model = segModel.absolutePath),
                numThreads = 2,
                debug = false,
                provider = "cpu",
            ),
            embedding = SpeakerEmbeddingExtractorConfig(
                model = embModel.absolutePath,
                numThreads = 2,
                debug = false,
                provider = "cpu",
            ),
            clustering = FastClusteringConfig(numClusters = -1, threshold = 0.5f),
            minDurationOn = 0.3f,
            minDurationOff = 0.5f,
        )
        val diarizer = OfflineSpeakerDiarization(/* assetManager = */ null, config)
        try {
            val segments = diarizer.process(samples)
            val spans = segments.map { seg ->
                SpeakerSpan(
                    speakerId = "S${seg.speaker}",
                    startMs = (seg.start * 1000f).toLong().coerceAtLeast(0L),
                    endMs = (seg.end * 1000f).toLong().coerceAtLeast(0L),
                )
            }
            val embeddingsBySpeaker = computeSpeakerEmbeddings(
                samples = samples,
                sampleRateHz = sampleRateHz,
                spans = spans,
                embeddingModelPath = embModel.absolutePath,
            )
            val named = mutableMapOf<String, String>()
            if (voiceProfiles != null) {
                for ((speakerId, embedding) in embeddingsBySpeaker) {
                    if (recordingId != null) {
                        voiceProfiles.cacheClusterEmbedding(recordingId, speakerId, embedding)
                    }
                    val match = voiceProfiles.match(embedding)
                    if (match != null) {
                        named[speakerId] = match.displayName
                    }
                }
            }
            return DiarizationResult(
                spans = spans,
                namedSpeakers = named,
                engineId = "local-diarizer",
                modelId = modelId,
            )
        } finally {
            diarizer.release()
        }
    }

    actual suspend fun complete(
        request: LlmRequest,
        modelId: String,
        modelPath: String,
    ): LlmResult {
        val weights = findLlmWeights(File(modelPath))
            ?: throw IllegalStateException(
                "Local LLM weights missing under $modelPath for modelId=$modelId. " +
                    "Expected *.litertlm / *.task after download+extract. " +
                    "Download Gemma in Model manager.",
            )
        val engine = Engine(
            EngineConfig(
                modelPath = weights.absolutePath,
                backend = Backend.CPU(),
            ),
        )
        engine.initialize()
        try {
            val system = buildString {
                append(request.systemPrompt)
                if (request.outputLanguage != "auto") {
                    append("\nRespond in language: ${request.outputLanguage}.")
                }
            }
            val conversation = engine.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(system),
                    maxOutputToken = request.maxOutputTokens,
                ),
            )
            try {
                val response = conversation.sendMessage(request.userPrompt)
                val text = response.contents.contents
                    .mapNotNull { (it as? Content.Text)?.text }
                    .joinToString("")
                    .trim()
                if (text.isEmpty()) {
                    throw IllegalStateException(
                        "LiteRT-LM returned empty text for modelId=$modelId path=${weights.absolutePath}",
                    )
                }
                return LlmResult(
                    text = text,
                    engineId = "local-llm",
                    modelId = modelId,
                )
            } finally {
                conversation.close()
            }
        } finally {
            engine.close()
        }
    }

    private fun computeSpeakerEmbeddings(
        samples: FloatArray,
        sampleRateHz: Int,
        spans: List<SpeakerSpan>,
        embeddingModelPath: String,
    ): Map<String, FloatArray> {
        if (spans.isEmpty()) return emptyMap()
        val extractor = SpeakerEmbeddingExtractor(
            /* assetManager = */ null,
            SpeakerEmbeddingExtractorConfig(
                model = embeddingModelPath,
                numThreads = 2,
                debug = false,
                provider = "cpu",
            ),
        )
        try {
            val bySpeaker = spans.groupBy { it.speakerId }
            val out = mutableMapOf<String, FloatArray>()
            for ((speakerId, speakerSpans) in bySpeaker) {
                val longest = speakerSpans.maxBy { it.endMs - it.startMs }
                val start = ((longest.startMs / 1000.0) * sampleRateHz).toInt()
                val end = ((longest.endMs / 1000.0) * sampleRateHz).toInt()
                val slice = sliceFloats(samples, start, end)
                if (slice.isEmpty()) continue
                val stream: OnlineStream = extractor.createStream()
                try {
                    stream.acceptWaveform(slice, sampleRateHz)
                    stream.inputFinished()
                    if (!extractor.isReady(stream)) continue
                    out[speakerId] = extractor.compute(stream)
                } finally {
                    stream.release()
                }
            }
            return out
        } finally {
            extractor.release()
        }
    }

    private fun buildRecognizerConfig(
        modelId: String,
        modelDir: File,
        languageHint: String?,
    ): OfflineRecognizerConfig? {
        val lang = languageHint?.takeIf { it.isNotBlank() && it != "auto" }.orEmpty()
        return when {
            modelId.contains("whisper", ignoreCase = true) -> whisperConfig(modelDir, lang)
            modelId.contains("sensevoice", ignoreCase = true) ||
                modelId.contains("sense-voice", ignoreCase = true) -> senseVoiceConfig(modelDir, lang)
            else -> transducerConfig(modelDir)
        }
    }

    private fun transducerConfig(modelDir: File): OfflineRecognizerConfig? {
        val tokens = findFile(modelDir, listOf("tokens.txt"))
            ?: findFileMatching(modelDir) { it == "tokens.txt" }
        val encoder = findFileMatching(modelDir) { it.contains("encoder") && it.endsWith(".onnx") }
        val decoder = findFileMatching(modelDir) { it.contains("decoder") && it.endsWith(".onnx") }
        val joiner = findFileMatching(modelDir) { it.contains("joiner") && it.endsWith(".onnx") }
        if (tokens == null || encoder == null || decoder == null || joiner == null) return null
        return OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80, dither = 0f),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = encoder.absolutePath,
                    decoder = decoder.absolutePath,
                    joiner = joiner.absolutePath,
                ),
                tokens = tokens.absolutePath,
                numThreads = 2,
                provider = "cpu",
                modelType = "nemo_transducer",
            ),
        )
    }

    private fun whisperConfig(modelDir: File, language: String): OfflineRecognizerConfig? {
        val tokens = findFileMatching(modelDir) { it.contains("tokens") && it.endsWith(".txt") }
        val encoder = findFileMatching(modelDir) {
            it.contains("encoder") && it.endsWith(".onnx")
        }
        val decoder = findFileMatching(modelDir) {
            it.contains("decoder") && it.endsWith(".onnx")
        }
        if (tokens == null || encoder == null || decoder == null) return null
        return OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80, dither = 0f),
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = encoder.absolutePath,
                    decoder = decoder.absolutePath,
                    language = language.ifBlank { "en" },
                    task = "transcribe",
                ),
                tokens = tokens.absolutePath,
                numThreads = 2,
                provider = "cpu",
                modelType = "whisper",
            ),
        )
    }

    private fun senseVoiceConfig(modelDir: File, language: String): OfflineRecognizerConfig? {
        val tokens = findFile(modelDir, listOf("tokens.txt"))
            ?: findFileMatching(modelDir) { it.endsWith("tokens.txt") }
        val model = findFileMatching(modelDir) {
            it.startsWith("model") && it.endsWith(".onnx")
        }
        if (tokens == null || model == null) return null
        return OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80, dither = 0f),
            modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = model.absolutePath,
                    language = language.ifBlank { "auto" },
                    useInverseTextNormalization = true,
                ),
                tokens = tokens.absolutePath,
                numThreads = 2,
                provider = "cpu",
            ),
        )
    }

    private fun missingAsrConfigMessage(modelId: String, modelDir: File): String {
        val present = modelDir.walkTopDown().filter { it.isFile }.map { it.name }.toList()
        return when {
            modelId.contains("whisper", ignoreCase = true) ->
                "Whisper ASR config incomplete under ${modelDir.absolutePath}. " +
                    "Need *tokens*.txt, *encoder*.onnx, *decoder*.onnx. Present: $present"
            modelId.contains("sense", ignoreCase = true) ->
                "SenseVoice ASR config incomplete under ${modelDir.absolutePath}. " +
                    "Need tokens.txt and model*.onnx. Present: $present"
            else ->
                "Parakeet/transducer ASR config incomplete under ${modelDir.absolutePath}. " +
                    "Need tokens.txt, encoder*.onnx, decoder*.onnx, joiner*.onnx. Present: $present"
        }
    }

    private fun timestampsToSegments(
        text: String,
        tokens: Array<String>,
        timestamps: FloatArray,
    ): List<TranscriptSegment> {
        if (tokens.isEmpty() || timestamps.isEmpty()) {
            return listOf(
                TranscriptSegment(startMs = 0, endMs = 0, text = text),
            )
        }
        val startMs = (timestamps.first() * 1000f).toLong().coerceAtLeast(0L)
        val endMs = (timestamps.last() * 1000f).toLong().coerceAtLeast(startMs)
        return listOf(TranscriptSegment(startMs = startMs, endMs = endMs, text = text))
    }

    private fun findFile(dir: File, names: List<String>): File? {
        for (name in names) {
            val direct = File(dir, name)
            if (direct.isFile) return direct
        }
        return dir.walkTopDown().firstOrNull { it.isFile && it.name in names }
    }

    private fun findFileMatching(dir: File, predicate: (String) -> Boolean): File? =
        dir.walkTopDown()
            .filter { it.isFile }
            .firstOrNull { predicate(it.name.lowercase()) }

    private fun findOnnx(dir: File): File? {
        if (dir.isFile && dir.name.endsWith(".onnx", ignoreCase = true)) return dir
        if (!dir.exists()) return null
        return dir.walkTopDown().firstOrNull { it.isFile && it.name.endsWith(".onnx", ignoreCase = true) }
    }

    private fun findLlmWeights(dir: File): File? {
        if (dir.isFile && isLlmFile(dir.name)) return dir
        if (!dir.exists()) return null
        return dir.walkTopDown().firstOrNull { it.isFile && isLlmFile(it.name) }
    }

    private fun isLlmFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".litertlm") || lower.endsWith(".task") ||
            lower.endsWith(".gguf") || lower.endsWith(".tflite")
    }

    private fun isArchiveName(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".tar.bz2") || lower.endsWith(".tar.gz") ||
            lower.endsWith(".tgz") || lower.endsWith(".zip") || lower.endsWith(".tbz2")
    }
}
