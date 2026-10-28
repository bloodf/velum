package dev.velum.ai.local

import dev.velum.ai.createDownloadHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update

enum class LocalModelKind {
    Asr,
    Diarization,
    Embedding,
    Llm,
}

enum class LocalModelStatus {
    NotDownloaded,
    Downloading,
    Ready,
    Failed,
    /** Catalog entry has no download URL — cannot fake Ready. */
    BlockedOnUrl,
}

/**
 * On-device model catalog. Weights are never in the APK / git —
 * they land under device storage via [LocalModelManager].
 */
data class LocalModelSpec(
    val id: String,
    val displayName: String,
    val kind: LocalModelKind,
    val approxBytes: Long,
    val license: String,
    val notes: String,
    val languages: String,
    /** Direct download URL (e.g. k2-fsa GitHub release). Null = blocked until owner supplies. */
    val downloadUrl: String? = null,
    /** Optional hex SHA-256 of the downloaded artifact. */
    val sha256: String? = null,
    val archiveFileName: String = "model.bin",
)

private const val ASR_MODELS =
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"
private const val SPEAKER_SEG_MODELS =
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models"
private const val SPEAKER_EMB_MODELS =
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models"

val DefaultLocalCatalog: List<LocalModelSpec> = listOf(
    LocalModelSpec(
        id = "parakeet-tdt-0.6b-v3-int8",
        displayName = "Parakeet TDT 0.6B v3",
        kind = LocalModelKind.Asr,
        approxBytes = 465L * 1024 * 1024,
        license = "CC-BY-4.0",
        notes = "High accuracy for 25 European languages, including Portuguese. ~465 MB.",
        languages = "25 European languages (pt, en, es, fr, de, …)",
        downloadUrl = "$ASR_MODELS/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2",
        sha256 = "5793d0fd397c5778d2cf2126994d58e9d56b1be7c04d13c7a15bb1b4eafb16bf",
        archiveFileName = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2",
    ),
    LocalModelSpec(
        id = "whisper-large-v3-turbo",
        displayName = "Whisper large-v3-turbo",
        kind = LocalModelKind.Asr,
        approxBytes = 538L * 1024 * 1024,
        license = "MIT",
        notes = "Best for non-European languages and mixed-language audio. ~538 MB.",
        languages = "About 99 languages (quality varies by language)",
        downloadUrl = "$ASR_MODELS/sherpa-onnx-whisper-turbo.tar.bz2",
        sha256 = "b11acbbcd660b44a8e0df33724feb5aaa709cf65668f2823d59f656312544f22",
        archiveFileName = "sherpa-onnx-whisper-turbo.tar.bz2",
    ),
    LocalModelSpec(
        id = "whistle",
        displayName = "Whistle (low-end EU)",
        kind = LocalModelKind.Asr,
        approxBytes = 16_919_407L,
        license = "Apache-2.0",
        notes = "Smallest option for low-memory phones. English and several European languages. ~17 MB.",
        languages = "en, de, fr, es, it, nl, pl",
        downloadUrl = "https://huggingface.co/Cactus-Compute/whistle/resolve/main/whistle.cact",
        sha256 = "b6e02f048568ac5d01a2042556c658061e699acbc0aa2a1439f52f3d461dffeb",
        archiveFileName = "whistle.cact",
    ),
    LocalModelSpec(
        id = "whisper-tiny",
        displayName = "Whisper Tiny (low-end)",
        kind = LocalModelKind.Asr,
        approxBytes = 111L * 1024 * 1024,
        license = "MIT",
        notes = "Light multilingual option for low-memory phones — includes Portuguese. ~111 MB.",
        languages = "About 99 languages (includes Portuguese)",
        downloadUrl = "$ASR_MODELS/sherpa-onnx-whisper-tiny.tar.bz2",
        sha256 = "c46116994e539aa165266d96b325252728429c12535eb9d8b6a2b10f129e66b1",
        archiveFileName = "sherpa-onnx-whisper-tiny.tar.bz2",
    ),
    LocalModelSpec(
        id = "sensevoice-small",
        displayName = "SenseVoice Small",
        kind = LocalModelKind.Asr,
        approxBytes = 156L * 1024 * 1024,
        license = "FunASR (check)",
        notes = "Light option for Chinese, Cantonese, Japanese, and Korean. ~156 MB.",
        languages = "zh, yue, en, ja, ko",
        downloadUrl = "$ASR_MODELS/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2",
        sha256 = "7d1efa2138a65b0b488df37f8b89e3d91a60676e416f515b952358d83dfd347e",
        archiveFileName = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2",
    ),
    LocalModelSpec(
        id = "pyannote-segmentation-3.0",
        displayName = "pyannote segmentation 3.0",
        kind = LocalModelKind.Diarization,
        approxBytes = 5L * 1024 * 1024,
        license = "MIT",
        notes = "Detects when speakers change in a recording. ~5 MB.",
        languages = "Works in any language",
        downloadUrl =
            "$SPEAKER_SEG_MODELS/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2",
        sha256 = "24615ee884c897d9d2ba09bb4d30da6bb1b15e685065962db5b02e76e4996488",
        archiveFileName = "sherpa-onnx-pyannote-segmentation-3-0.tar.bz2",
    ),
    LocalModelSpec(
        id = "3dspeaker-eres2net-base",
        displayName = "3D-Speaker ERes2Net base",
        kind = LocalModelKind.Embedding,
        approxBytes = 38L * 1024 * 1024,
        license = "Apache-2.0",
        notes = "Matches speakers to saved voice profiles. Recommended default. ~38 MB.",
        languages = "Multilingual",
        downloadUrl =
            "$SPEAKER_EMB_MODELS/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx",
        sha256 = "1a331345f04805badbb495c775a6ddffcdd1a732567d5ec8b3d5749e3c7a5e4b",
        archiveFileName = "3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx",
    ),
    LocalModelSpec(
        id = "wespeaker-voxceleb",
        displayName = "WeSpeaker (VoxCeleb)",
        kind = LocalModelKind.Embedding,
        approxBytes = 28L * 1024 * 1024,
        license = "Apache-2.0",
        notes = "Alternate speaker matching trained mainly on English. ~28 MB.",
        languages = "English-focused",
        downloadUrl = "$SPEAKER_EMB_MODELS/wespeaker_en_voxceleb_CAM++.onnx",
        sha256 = "c46fad10b5f81e1aa4a60c162714208577093655076c5450f8c469e522ec54ef",
        archiveFileName = "wespeaker_en_voxceleb_CAM++.onnx",
    ),
    LocalModelSpec(
        id = "titanet-small",
        displayName = "NeMo TitaNet-small",
        kind = LocalModelKind.Embedding,
        approxBytes = 39L * 1024 * 1024,
        license = "CC-BY-4.0",
        notes = "Alternate speaker matching. ~39 MB.",
        languages = "English-focused",
        downloadUrl = "$SPEAKER_EMB_MODELS/nemo_en_titanet_small.onnx",
        sha256 = "ad4a1802485d8b34c722d2a9d04249662f2ece5d28a7a039063ca22f515a789e",
        archiveFileName = "nemo_en_titanet_small.onnx",
    ),
    LocalModelSpec(
        id = "streaming-zipformer-en-20m-mobile",
        displayName = "Streaming Zipformer EN 20M (mobile)",
        kind = LocalModelKind.Asr,
        approxBytes = 103L * 1024 * 1024,
        license = "Apache-2.0",
        notes = "Faster live captions for English mic recordings. ~103 MB.",
        languages = "English",
        downloadUrl =
            "$ASR_MODELS/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17-mobile.tar.bz2",
        sha256 = "753a5362538539212442efbfb8dd5748db82e35e7deaec5330f49c56e81e40fd",
        archiveFileName = "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17-mobile.tar.bz2",
    ),
    LocalModelSpec(
        id = "streaming-zipformer-es-kroko",
        displayName = "Streaming Zipformer ES (Kroko)",
        kind = LocalModelKind.Asr,
        approxBytes = 119L * 1024 * 1024,
        license = "Apache-2.0",
        notes = "Faster live captions for Spanish mic recordings. ~119 MB.",
        languages = "Spanish",
        downloadUrl =
            "$ASR_MODELS/sherpa-onnx-streaming-zipformer-es-kroko-2025-08-06.tar.bz2",
        sha256 = "31b2230a95d23290b308b393da930015a4b2105cb3abb9367aed35f7fcf29cf1",
        archiveFileName = "sherpa-onnx-streaming-zipformer-es-kroko-2025-08-06.tar.bz2",
    ),
    LocalModelSpec(
        id = "streaming-zipformer-multilang-2025-02",
        displayName = "Streaming Zipformer multilingual",
        kind = LocalModelKind.Asr,
        approxBytes = 247L * 1024 * 1024,
        license = "Apache-2.0",
        notes = "Faster live captions for several Asian and other languages (not Portuguese). ~247 MB.",
        languages = "ar, en, id, ja, ru, th, vi, zh",
        downloadUrl =
            "$ASR_MODELS/sherpa-onnx-streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10.tar.bz2",
        sha256 = "28044b67324f7f831689f0a3761473dd2ade380e93aa53f1dbcd479ef71c40d4",
        archiveFileName =
            "sherpa-onnx-streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10.tar.bz2",
    ),
    LocalModelSpec(
        id = "gemma-4-e4b",
        displayName = "Gemma 4 E4B",
        kind = LocalModelKind.Llm,
        approxBytes = 3650L * 1024 * 1024,
        license = "Apache-2.0",
        notes = "On-device summaries and Ask AI — higher quality. ~3.6 GB.",
        languages = "35+ tuned languages",
        downloadUrl =
            "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
        sha256 = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0",
        archiveFileName = "gemma-4-E4B-it.litertlm",
    ),
    LocalModelSpec(
        id = "gemma-4-e2b",
        displayName = "Gemma 4 E2B",
        kind = LocalModelKind.Llm,
        approxBytes = 2580L * 1024 * 1024,
        license = "Apache-2.0",
        notes = "On-device summaries and Ask AI — lighter download. ~2.5 GB.",
        languages = "35+ tuned languages",
        downloadUrl =
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
        sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
        archiveFileName = "gemma-4-E2B-it.litertlm",
    ),
    LocalModelSpec(
        id = "qwen3-4b-instruct-2507",
        displayName = "Qwen3-4B-Instruct-2507",
        kind = LocalModelKind.Llm,
        approxBytes = 2500L * 1024 * 1024,
        license = "Apache-2.0",
        notes = "Alternate on-device summaries. ~2.5 GB.",
        languages = "Many languages",
        downloadUrl =
            "https://huggingface.co/unsloth/Qwen3-4B-Instruct-2507-GGUF/resolve/main/Qwen3-4B-Instruct-2507-Q4_K_M.gguf",
        sha256 = "3605803b982cb64aead44f6c1b2ae36e3acdb41d8e46c8a94c6533bc4c67e597",
        archiveFileName = "Qwen3-4B-Instruct-2507-Q4_K_M.gguf",
    ),
)

data class LocalModelState(
    val spec: LocalModelSpec,
    val status: LocalModelStatus = LocalModelStatus.NotDownloaded,
    val progress: Float = 0f,
    val error: String? = null,
)

fun createLocalModelManager(
    storeRoot: ModelStoreDir = defaultModelStoreRoot(),
    catalog: List<LocalModelSpec> = DefaultLocalCatalog,
    httpClient: HttpClient = createDownloadHttpClient(),
): LocalModelManager = LocalModelManager(storeRoot, httpClient, catalog)

class LocalModelManager(
    private val storeRoot: ModelStoreDir = defaultModelStoreRoot(),
    private val httpClient: HttpClient,
    catalog: List<LocalModelSpec> = DefaultLocalCatalog,
) {
    private val _states = MutableStateFlow(
        catalog.map { initialState(it) },
    )
    val states: StateFlow<List<LocalModelState>> = _states.asStateFlow()

    fun stateFor(id: String): LocalModelState? = _states.value.firstOrNull { it.spec.id == id }

    fun isReady(id: String): Boolean = stateFor(id)?.status == LocalModelStatus.Ready

    /** Weights directory (extracted) when Ready; null otherwise. */
    fun modelPath(id: String): ModelStoreDir? {
        val spec = stateFor(id)?.spec ?: return null
        if (!isReady(id)) return null
        val dir = storeRoot.resolve(id)
        return ModelReadiness.resolveWeightsDir(dir, spec)
    }

    fun refreshFromDisk() {
        _states.update { list -> list.map { initialState(it.spec) } }
    }

    fun download(id: String): Flow<LocalModelState> = flow {
        val current = stateFor(id) ?: return@flow
        val url = current.spec.downloadUrl
        if (url.isNullOrBlank()) {
            update(id) {
                it.copy(
                    status = LocalModelStatus.BlockedOnUrl,
                    progress = 0f,
                    error = "No download URL for ${it.spec.id}. Blocked until a release URL is set.",
                )
            }
            emit(requireNotNull(stateFor(id)))
            return@flow
        }
        if (!url.startsWith("https://")) {
            update(id) {
                it.copy(
                    status = LocalModelStatus.Failed,
                    progress = 0f,
                    error = "Model download URL must use https:// (got: $url)",
                )
            }
            emit(requireNotNull(stateFor(id)))
            return@flow
        }
        val expectedSha = current.spec.sha256?.trim().orEmpty()
        if (expectedSha.isEmpty()) {
            update(id) {
                it.copy(
                    status = LocalModelStatus.BlockedOnUrl,
                    progress = 0f,
                    error = "SHA-256 required before download for ${it.spec.id}. Blocked until checksum is pinned.",
                )
            }
            emit(requireNotNull(stateFor(id)))
            return@flow
        }

        update(id) { it.copy(status = LocalModelStatus.Downloading, progress = 0f, error = null) }
        emit(requireNotNull(stateFor(id)))

        val dir = storeRoot.resolve(id)
        dir.createDirectories()
        val dest = dir.resolve(current.spec.archiveFileName)
        val partial = dir.resolve("${current.spec.archiveFileName}.partial")
        if (partial.exists()) partial.deleteRecursively()

        try {
            val digest = createSha256Digest()
            httpClient.prepareGet(url).execute { response ->
                if (!response.status.isSuccess()) {
                    throw IllegalStateException("Download failed: HTTP ${response.status.value}")
                }
                val total = response.contentLength() ?: current.spec.approxBytes.coerceAtLeast(1L)
                val channel: ByteReadChannel = response.bodyAsChannel()
                val buffer = ByteArray(64 * 1024)
                var written = 0L
                while (!channel.isClosedForRead) {
                    val n = channel.readAvailable(buffer, 0, buffer.size)
                    if (n <= 0) break
                    digest.update(buffer, 0, n)
                    partial.appendBytes(buffer.copyOf(n))
                    written += n
                    val progress = (written.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 0.99f)
                    update(id) { it.copy(status = LocalModelStatus.Downloading, progress = progress) }
                    emit(requireNotNull(stateFor(id)))
                }
            }

            val actual = digest.digestHex()
            if (!actual.equals(expectedSha, ignoreCase = true)) {
                partial.deleteRecursively()
                throw IllegalStateException("SHA-256 mismatch for $id")
            }

            if (dest.exists()) dest.deleteRecursively()
            if (!partial.renameTo(dest)) {
                // Never load multi-hundred-MB archives fully into heap on rename failure.
                streamCopyModelFile(partial, dest)
                partial.deleteRecursively()
            }

            if (ModelArchiveExtractor.isArchive(current.spec.archiveFileName)) {
                update(id) {
                    it.copy(status = LocalModelStatus.Downloading, progress = 0.99f, error = null)
                }
                emit(requireNotNull(stateFor(id)))
                ModelArchiveExtractor.extract(dest, dir)
            }

            val missing = ModelReadiness.missingRequirements(current.spec, dir)
            if (missing.isNotEmpty()) {
                throw IllegalStateException(
                    "Model '$id' downloaded but not Ready — missing ${missing.joinToString(", ")}. " +
                        "Expected layout: ${ModelReadiness.layoutFor(current.spec).describe()}",
                )
            }

            update(id) { it.copy(status = LocalModelStatus.Ready, progress = 1f, error = null) }
            emit(requireNotNull(stateFor(id)))
        } catch (t: Throwable) {
            runCatching { partial.deleteRecursively() }
            update(id) {
                it.copy(
                    status = LocalModelStatus.Failed,
                    progress = 0f,
                    error = t.message ?: t.toString(),
                )
            }
            emit(requireNotNull(stateFor(id)))
        }
    }

    fun delete(id: String) {
        val dir = storeRoot.resolve(id)
        if (dir.exists()) dir.deleteRecursively()
        update(id) { initialState(it.spec).copy(status = statusForMissingUrl(it.spec)) }
    }

    private fun initialState(spec: LocalModelSpec): LocalModelState {
        val dir = storeRoot.resolve(spec.id)
        return when {
            ModelReadiness.isReady(spec, dir) ->
                LocalModelState(spec, LocalModelStatus.Ready, progress = 1f)
            spec.downloadUrl.isNullOrBlank() ->
                LocalModelState(
                    spec,
                    LocalModelStatus.BlockedOnUrl,
                    error = "No download URL. Blocked until a release URL is set.",
                )
            spec.sha256.isNullOrBlank() ->
                LocalModelState(
                    spec,
                    LocalModelStatus.BlockedOnUrl,
                    error = "SHA-256 required before download. Blocked until checksum is pinned.",
                )
            dir.exists() && dir.listChildren().isNotEmpty() ->
                LocalModelState(
                    spec,
                    LocalModelStatus.Failed,
                    error = "Incomplete model files under ${dir.absolutePath}: " +
                        ModelReadiness.missingRequirements(spec, dir).joinToString(", "),
                )
            else -> LocalModelState(spec, LocalModelStatus.NotDownloaded)
        }
    }

    private fun statusForMissingUrl(spec: LocalModelSpec): LocalModelStatus =
        when {
            spec.downloadUrl.isNullOrBlank() -> LocalModelStatus.BlockedOnUrl
            spec.sha256.isNullOrBlank() -> LocalModelStatus.BlockedOnUrl
            else -> LocalModelStatus.NotDownloaded
        }

    private fun update(id: String, transform: (LocalModelState) -> LocalModelState) {
        _states.update { list ->
            list.map { if (it.spec.id == id) transform(it) else it }
        }
    }

}

/**
 * Languages Velum treats as product-important (must have a working local ASR path).
 * Whistle covers a subset; Whisper Tiny / Parakeet / SenseVoice / Whisper turbo cover the rest.
 * See docs/WHISTLE_EVAL.md.
 */
object ImportantLanguages {
    val codes: Set<String> = setOf(
        "pt", "en", "es", "fr", "de", "it", "nl", "pl",
        "ja", "zh", "ko", "ru", "ar", "hi", "tr",
    )
}

/** Language + device-tier router for local ASR — see ARCHITECTURE_AI.md / WHISTLE_EVAL.md. */
object LocalAsrRouter {
    const val WHISTLE_ID: String = "whistle"
    const val TINY_ID: String = "whisper-tiny"
    const val STANDARD_ID: String = "parakeet-tdt-0.6b-v3-int8"
    const val QUALITY_ID: String = "whisper-large-v3-turbo"
    const val CJK_LIGHT_ID: String = "sensevoice-small"

    /** Languages Whistle (Needle `.cact`) supports. */
    val whistleLanguages: Set<String> = setOf("en", "de", "fr", "es", "it", "nl", "pl")

    private val parakeetEuropean = setOf(
        "bg", "hr", "cs", "da", "nl", "en", "et", "fi", "fr", "de", "el", "hu",
        "it", "lv", "lt", "mt", "pl", "pt", "ro", "sk", "sl", "es", "sv", "uk", "ru",
    )
    private val cjkLight = setOf("zh", "yue", "ja", "ko")

    fun normalizeLang(languageHint: String?): String? =
        languageHint?.lowercase()?.substringBefore('-')?.takeIf { it.isNotBlank() && it != "auto" }

    fun supportsWhistle(languageHint: String?): Boolean =
        normalizeLang(languageHint) in whistleLanguages

    /**
     * Low-end / Tiny size pick: Whistle when the language is in its set, else Whisper Tiny
     * (covers Portuguese and other important languages).
     */
    fun chooseLowEndModelId(languageHint: String?): String =
        if (supportsWhistle(languageHint)) WHISTLE_ID else TINY_ID

    fun chooseModelId(
        languageHint: String?,
        mixedAudio: Boolean = false,
        tier: AsrSizeTier = AsrSizeTier.Auto,
        deviceClass: DeviceComputeClass = detectDeviceComputeClass(),
    ): String {
        when (tier) {
            AsrSizeTier.Tiny -> return chooseLowEndModelId(languageHint)
            AsrSizeTier.Quality -> return if (mixedAudio) QUALITY_ID else languageDefault(languageHint, preferQuality = true)
            AsrSizeTier.Standard -> return languageDefault(languageHint, preferQuality = false)
            AsrSizeTier.Auto -> Unit
        }
        if (deviceClass == DeviceComputeClass.Low) return chooseLowEndModelId(languageHint)
        if (mixedAudio) return QUALITY_ID
        return languageDefault(languageHint, preferQuality = deviceClass == DeviceComputeClass.High && mixedAudio)
    }

    private fun languageDefault(languageHint: String?, preferQuality: Boolean): String {
        val lang = normalizeLang(languageHint)
        return when {
            preferQuality && lang !in parakeetEuropean && lang !in cjkLight -> QUALITY_ID
            lang == null -> STANDARD_ID
            lang in parakeetEuropean -> STANDARD_ID
            lang in cjkLight -> CJK_LIGHT_ID
            else -> QUALITY_ID
        }
    }
}

/** Chunked file copy — used when atomic rename is unavailable across volumes. */
internal fun streamCopyModelFile(source: ModelStoreDir, dest: ModelStoreDir, chunkBytes: Int = 1024 * 1024) {
    val total = source.length()
    if (total <= 0L) {
        dest.writeBytes(ByteArray(0))
        return
    }
    var offset = 0L
    var first = true
    while (offset < total) {
        val len = minOf(chunkBytes.toLong(), total - offset).toInt()
        val chunk = source.readRange(offset, len)
        if (first) {
            dest.writeBytes(chunk)
            first = false
        } else {
            dest.appendBytes(chunk)
        }
        offset += chunk.size
    }
}
