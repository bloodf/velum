package dev.velum.ai.needle

import dev.velum.ai.engine.TranscriptSegment
import dev.velum.ai.engine.TranscriptionResult
import dev.velum.ai.local.pcm16LeToFloats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

actual object NeedleWhistleEngine {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private var loadedCactPath: String? = null

    actual val isNativeAvailable: Boolean
        get() = NeedleNative.available

    actual suspend fun transcribe(
        pcm16LeOrEncoded: ByteArray,
        sampleRateHz: Int,
        languageHint: String?,
        cactPath: String,
        keywords: String?,
    ): TranscriptionResult = withContext(Dispatchers.Default) {
        NeedleNative.requireAvailable()
        val cactFile = resolveCactFile(cactPath)
        if (!cactFile.exists() || cactFile.length() <= 0L) {
            throw IllegalStateException(
                "Whistle weights missing at ${cactFile.absolutePath}. " +
                    "Download model id 'whistle' in Model manager (SHA-verified).",
            )
        }
        if (sampleRateHz != 16_000) {
            throw IllegalStateException(
                "Whistle/Needle requires 16 kHz mono PCM (got ${sampleRateHz} Hz). " +
                    "Normalize audio before Local ASR.",
            )
        }
        @Suppress("UNUSED_PARAMETER")
        val ignoredKeywords = keywords
        mutex.withLock {
            ensureLoaded(cactFile)
            val floats = pcm16LeToFloats(pcm16LeOrEncoded)
            val lang = languageHint
                ?.lowercase()
                ?.substringBefore('-')
                ?.takeIf { it in WHISTLE_LANGS_FOR_FORCE }
            val raw = NeedleNative.nativeTranscribe(floats, lang, /* wordTimestamps = */ true)
                ?: throw IllegalStateException(
                    "Needle whistle transcription failed: ${NeedleNative.nativeLastError()}",
                )
            parseResult(raw)
        }
    }

    private fun ensureLoaded(cactFile: File) {
        val path = cactFile.absolutePath
        if (loadedCactPath == path) return
        val bytes = cactFile.readBytes()
        if (!NeedleNative.nativeLoadCact(bytes)) {
            throw IllegalStateException(
                "needle_load failed for $path: ${NeedleNative.nativeLastError()}",
            )
        }
        loadedCactPath = path
    }

    private fun resolveCactFile(modelPath: String): File {
        val root = File(modelPath)
        if (root.isFile && root.name.endsWith(".cact", ignoreCase = true)) return root
        val direct = File(root, "whistle.cact")
        if (direct.exists()) return direct
        return root.listFiles()
            ?.firstOrNull { it.isFile && it.name.endsWith(".cact", ignoreCase = true) }
            ?: direct
    }

    private fun parseResult(raw: String): TranscriptionResult {
        val obj = json.parseToJsonElement(raw).jsonObject
        val text = obj["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (text.isEmpty()) {
            throw IllegalStateException(
                "Whistle returned empty transcript (silence/noise or unsupported language). " +
                    "For Portuguese and other non-Whistle languages use Whisper Tiny on low-end.",
            )
        }
        val language = obj["language"]?.jsonPrimitive?.contentOrNull
        val words = obj["words"]?.jsonArray.orEmpty()
        val segments = words.mapNotNull { el ->
            val w = el.jsonObject
            val word = w["word"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val start = w["start"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0
            val end = w["end"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: start
            TranscriptSegment(
                text = word,
                startMs = (start * 1000.0).toLong().coerceAtLeast(0L),
                endMs = (end * 1000.0).toLong().coerceAtLeast(0L),
            )
        }
        return TranscriptionResult(
            text = text,
            segments = segments,
            detectedLanguage = language,
            engineId = "needle-whistle",
            modelId = "whistle",
        )
    }

    private val WHISTLE_LANGS_FOR_FORCE = setOf("en", "de", "fr", "es", "it", "nl", "pl")
}
