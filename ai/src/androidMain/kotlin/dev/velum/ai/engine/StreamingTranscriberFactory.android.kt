package dev.velum.ai.engine

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import dev.velum.ai.pipeline.BoundedPcmRingBuffer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Prefer [OnlineRecognizer] when the model pack looks streaming-capable
 * (SenseVoice/Parakeet streaming configs, or id/path containing online/streaming).
 * Otherwise overlapping-window OfflineRecognizer with partial Markdown emits.
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
    val dir = File(modelPath)
    return tryCreateOnlineStreaming(dir, modelId)
        ?: OverlappingChunkedOfflineStreamingTranscriber(modelPath = modelPath, modelId = modelId)
}

private fun tryCreateOnlineStreaming(modelDir: File, modelId: String): StreamingTranscriber? {
    if (!supportsOnlineRecognizer(modelId, modelDir)) return null
    val tokens = findFile(modelDir, "tokens.txt") ?: return null
    val encoder = findOnnx(modelDir, "encoder") ?: return null
    val decoder = findOnnx(modelDir, "decoder") ?: return null
    val joiner = findOnnx(modelDir, "joiner") ?: return null
    return try {
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80, dither = 0f),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = encoder.absolutePath,
                    decoder = decoder.absolutePath,
                    joiner = joiner.absolutePath,
                ),
                tokens = tokens.absolutePath,
                numThreads = 2,
                provider = "cpu",
                modelType = "zipformer2",
            ),
            enableEndpoint = true,
            decodingMethod = "greedy_search",
        )
        val recognizer = OnlineRecognizer(/* assetManager = */ null, config)
        OnlineStreamingTranscriber(recognizer, modelId)
    } catch (_: Throwable) {
        null
    }
}

/**
 * Streaming packs: explicit online/streaming in id or filenames.
 * Catalog Parakeet/SenseVoice offline packs stay on the overlapping Offline path.
 */
internal fun supportsOnlineRecognizer(modelId: String, modelDir: File): Boolean {
    val id = modelId.lowercase()
    if (id.contains("online") || id.contains("streaming")) return true
    if (id.contains("whisper") || id.contains("sensevoice") || id.contains("sense-voice")) {
        return false
    }
    val names = modelDir.walkTopDown().filter { it.isFile }.map { it.name.lowercase() }.toList()
    return names.any { it.contains("online") || it.contains("streaming") }
}

class OnlineStreamingTranscriber(
    private val recognizer: OnlineRecognizer,
    private val modelId: String,
) : StreamingTranscriber {
    override val engineId: String = "local-online-asr"
    override var onPartial: (markdown: String) -> Unit = {}

    private var sampleRate = 16_000
    private var stream: OnlineStream? = null
    private val committed = StringBuilder()
    private var started = false

    override fun start(
        sampleRateHz: Int,
        languageHint: String?,
        initialPrompt: String?,
    ) {
        started = true
        sampleRate = sampleRateHz
        committed.clear()
        stream?.release()
        stream = recognizer.createStream()
        onPartial("**partial** · 0:00\n")
    }

    override fun pushPcm(pcm16Le: ByteArray) {
        check(started) { "start() required" }
        val s = stream ?: return
        val samples = pcm16LeToFloats(pcm16Le)
        s.acceptWaveform(samples, sampleRate)
        while (recognizer.isReady(s)) {
            recognizer.decode(s)
        }
        val text = recognizer.getResult(s).text.trim()
        if (recognizer.isEndpoint(s)) {
            if (text.isNotBlank()) {
                if (committed.isNotEmpty()) committed.append(' ')
                committed.append(text)
            }
            recognizer.reset(s)
        }
        emitPartial(text)
    }

    override fun finish(): String {
        check(started) { "start() required" }
        started = false
        val s = stream
        if (s != null) {
            s.inputFinished()
            while (recognizer.isReady(s)) {
                recognizer.decode(s)
            }
            val text = recognizer.getResult(s).text.trim()
            if (text.isNotBlank()) {
                if (committed.isNotEmpty()) committed.append(' ')
                committed.append(text)
            }
            s.release()
            stream = null
        }
        recognizer.release()
        val md = "**Speaker 1** · 0:00\n${committed}\n"
        onPartial(md)
        return md
    }

    private fun emitPartial(live: String) {
        val body = buildString {
            if (committed.isNotEmpty()) append(committed).append('\n')
            if (live.isNotBlank()) append(live)
        }
        onPartial("**partial** · 0:00\n$body\n")
    }
}

/**
 * Overlapping OfflineRecognizer windows with frequent partial Markdown emits.
 * Window 12s / hop 3s keeps latency low without requiring an Online pack.
 */
class OverlappingChunkedOfflineStreamingTranscriber(
    private val modelPath: String,
    private val modelId: String,
    private val windowSeconds: Int = 12,
    private val hopSeconds: Int = 3,
    private val maxBufferedSeconds: Int = 10 * 60,
) : StreamingTranscriber {
    override val engineId: String = "local-streaming-asr"
    override var onPartial: (markdown: String) -> Unit = {}

    private var sampleRate = 16_000
    private var languageHint: String? = "auto"
    private var pcm = BoundedPcmRingBuffer.forMaxDuration(16_000, maxBufferedSeconds)
    private var lastEmitBytes = 0
    private var lastCommittedEnd = 0
    private val committed = StringBuilder()
    private var started = false
    private var totalPushedBytes = 0

    override fun start(
        sampleRateHz: Int,
        languageHint: String?,
        initialPrompt: String?,
    ) {
        started = true
        sampleRate = sampleRateHz
        this.languageHint = languageHint
        pcm = BoundedPcmRingBuffer.forMaxDuration(sampleRateHz, maxBufferedSeconds)
        lastEmitBytes = 0
        lastCommittedEnd = 0
        totalPushedBytes = 0
        committed.clear()
        onPartial("**partial** · 0:00\n")
    }

    override fun pushPcm(pcm16Le: ByteArray) {
        check(started) { "start() required" }
        val before = pcm.size
        pcm.write(pcm16Le)
        totalPushedBytes += pcm16Le.size
        if (pcm.size < before + pcm16Le.size) {
            val dropped = (before + pcm16Le.size) - pcm.size
            lastCommittedEnd = (lastCommittedEnd - dropped).coerceAtLeast(0)
            lastEmitBytes = (lastEmitBytes - dropped).coerceAtLeast(0)
        }
        val hop = hopBytes()
        if (pcm.size - lastEmitBytes >= hop) {
            emitPartial()
            lastEmitBytes = pcm.size
        }
    }

    override fun finish(): String {
        check(started) { "start() required" }
        started = false
        if (pcm.size > lastCommittedEnd) {
            val tail = decodeRange(lastCommittedEnd, pcm.size)
            if (tail.isNotBlank()) {
                if (committed.isNotEmpty()) committed.append(' ')
                committed.append(tail)
            }
        }
        val md = "**Speaker 1** · 0:00\n${committed}\n"
        onPartial(md)
        return md
    }

    private fun hopBytes(): Int = sampleRate * 2 * hopSeconds

    private fun windowBytes(): Int = sampleRate * 2 * windowSeconds

    private fun emitPartial() {
        val window = windowBytes()
        val hop = hopBytes()
        val commitUntil = (pcm.size - (window - hop)).coerceAtLeast(0)
        if (commitUntil > lastCommittedEnd) {
            val segment = decodeRange(lastCommittedEnd, commitUntil)
            if (segment.isNotBlank()) {
                if (committed.isNotEmpty()) committed.append(' ')
                committed.append(segment)
            }
            lastCommittedEnd = commitUntil
        }
        val liveStart = (pcm.size - window).coerceAtLeast(lastCommittedEnd)
        val live = decodeRange(liveStart, pcm.size)
        val elapsedMs = if (sampleRate <= 0) 0L else (totalPushedBytes / 2) * 1000L / sampleRate
        val body = buildString {
            if (committed.isNotEmpty()) append(committed).append('\n')
            if (live.isNotBlank()) append(live)
        }
        onPartial("**partial** · ${formatClock(elapsedMs)}\n$body\n")
    }

    private fun decodeRange(startByte: Int, endByte: Int): String {
        if (endByte <= startByte || pcm.size == 0) return ""
        val from = startByte.coerceIn(0, pcm.size)
        val to = endByte.coerceIn(from, pcm.size)
        if (to - from < sampleRate) return ""
        return decodeSamples(pcm16LeToFloats(pcm.copyRange(from, to)))
    }

    private fun decodeSamples(samples: FloatArray): String {
        if (samples.isEmpty()) return ""
        val config = buildOfflineRecognizerConfig(modelId, File(modelPath), languageHint)
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

    companion object {
        const val MAX_LIVE_BUFFER_SECONDS: Int = 10 * 60
    }
}

/** Alias for older references / STATUS docs. */
typealias ChunkedOfflineStreamingTranscriber = OverlappingChunkedOfflineStreamingTranscriber

private fun formatClock(elapsedMs: Long): String {
    val totalSec = (elapsedMs / 1000).coerceAtLeast(0)
    val m = totalSec / 60
    val s = totalSec % 60
    return "$m:${s.toString().padStart(2, '0')}"
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

private fun buildOfflineRecognizerConfig(
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
