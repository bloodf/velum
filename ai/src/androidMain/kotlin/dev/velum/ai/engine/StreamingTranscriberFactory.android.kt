package dev.velum.ai.engine

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Chunked OfflineRecognizer streaming: accumulates PCM, re-decodes on each push of
 * ~1s of audio so partial Markdown updates. Prefer OnlineRecognizer when wired later.
 */
actual fun createStreamingTranscriber(
    modelPath: String?,
    modelId: String,
): StreamingTranscriber {
    if (modelPath.isNullOrBlank()) {
        throw IllegalStateException(
            "StreamingTranscriber on Android requires a Ready local ASR model path. " +
                "Download Parakeet/Whisper/SenseVoice in Model manager.",
        )
    }
    return ChunkedOfflineStreamingTranscriber(modelPath = modelPath, modelId = modelId)
}

class ChunkedOfflineStreamingTranscriber(
    private val modelPath: String,
    private val modelId: String,
) : StreamingTranscriber {
    override val engineId: String = "local-streaming-asr"
    override var onPartial: (markdown: String) -> Unit = {}

    private var sampleRate = 16_000
    private var languageHint: String? = "auto"
    private val pcm = ArrayList<Byte>()
    private var lastEmitBytes = 0
    private var started = false

    override fun start(
        sampleRateHz: Int,
        languageHint: String?,
        initialPrompt: String?,
    ) {
        started = true
        sampleRate = sampleRateHz
        this.languageHint = languageHint
        pcm.clear()
        lastEmitBytes = 0
        onPartial("**partial** · 0:00\n")
    }

    override fun pushPcm(pcm16Le: ByteArray) {
        check(started) { "start() required" }
        for (b in pcm16Le) pcm.add(b)
        val minChunk = sampleRate * 2 // ~1s of PCM16 mono
        if (pcm.size - lastEmitBytes >= minChunk) {
            emitPartial()
            lastEmitBytes = pcm.size
        }
    }

    override fun finish(): String {
        check(started) { "start() required" }
        started = false
        val text = decodeAll()
        val md = "**Speaker 1** · 0:00\n$text\n"
        onPartial(md)
        return md
    }

    private fun emitPartial() {
        val text = decodeAll()
        if (text.isNotBlank()) {
            onPartial("**partial** · 0:00\n$text\n")
        }
    }

    private fun decodeAll(): String {
        if (pcm.isEmpty()) return ""
        val bytes = ByteArray(pcm.size) { pcm[it] }
        val samples = pcm16LeToFloats(bytes)
        val config = buildRecognizerConfig(modelId, File(modelPath), languageHint)
            ?: throw IllegalStateException(
                "Cannot build OfflineRecognizer for streaming modelId=$modelId path=$modelPath",
            )
        val recognizer = OfflineRecognizer(/* assetManager = */ null, config)
        try {
            val stream = recognizer.createStream()
            try {
                stream.acceptWaveform(samples, sampleRate)
                recognizer.decode(stream)
                return recognizer.getResult(stream).text.trim()
            } finally {
                stream.release()
            }
        } finally {
            recognizer.release()
        }
    }
}

private fun pcm16LeToFloats(pcm: ByteArray): FloatArray {
    val shorts = pcm.size / 2
    val out = FloatArray(shorts)
    val buf = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
    for (i in 0 until shorts) {
        out[i] = buf.short / 32768f
    }
    return out
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
    val tokens = findFile(modelDir, "tokens.txt") ?: return null
    val encoder = findOnnx(modelDir, "encoder") ?: return null
    val decoder = findOnnx(modelDir, "decoder") ?: return null
    val joiner = findOnnx(modelDir, "joiner") ?: return null
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
    val tokens = modelDir.walkTopDown().firstOrNull {
        it.isFile && it.name.contains("tokens") && it.name.endsWith(".txt")
    } ?: return null
    val encoder = findOnnx(modelDir, "encoder") ?: return null
    val decoder = findOnnx(modelDir, "decoder") ?: return null
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
    val tokens = findFile(modelDir, "tokens.txt") ?: return null
    val model = modelDir.walkTopDown().firstOrNull {
        it.isFile && it.name.startsWith("model") && it.name.endsWith(".onnx")
    } ?: return null
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

private fun findFile(dir: File, name: String): File? =
    dir.walkTopDown().firstOrNull { it.isFile && it.name == name }

private fun findOnnx(dir: File, needle: String): File? =
    dir.walkTopDown().firstOrNull {
        it.isFile && it.name.contains(needle) && it.name.endsWith(".onnx")
    }
