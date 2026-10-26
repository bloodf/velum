package dev.velum.ai.local

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

val DefaultLocalCatalog: List<LocalModelSpec> = listOf(
    LocalModelSpec(
        id = "parakeet-tdt-0.6b-v3-int8",
        displayName = "Parakeet TDT 0.6B v3",
        kind = LocalModelKind.Asr,
        approxBytes = 465L * 1024 * 1024,
        license = "CC-BY-4.0",
        notes = "Default for 25 European languages via sherpa-onnx",
        languages = "25 European (incl. pt, en, es, fr, de, …)",
        downloadUrl = "$ASR_MODELS/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2",
        archiveFileName = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2",
    ),
    LocalModelSpec(
        id = "whisper-large-v3-turbo",
        displayName = "Whisper large-v3-turbo",
        kind = LocalModelKind.Asr,
        approxBytes = 538L * 1024 * 1024,
        license = "MIT",
        notes = "Fallback for non-EU languages and mixed audio",
        languages = "~99 (quality varies)",
        downloadUrl = "$ASR_MODELS/sherpa-onnx-whisper-turbo.tar.bz2",
        archiveFileName = "sherpa-onnx-whisper-turbo.tar.bz2",
    ),
    LocalModelSpec(
        id = "sensevoice-small",
        displayName = "SenseVoice Small",
        kind = LocalModelKind.Asr,
        approxBytes = 156L * 1024 * 1024,
        license = "FunASR (check)",
        notes = "Light CJK option (zh / yue / ja / ko)",
        languages = "zh, yue, en, ja, ko",
        downloadUrl = "$ASR_MODELS/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2",
        archiveFileName = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2",
    ),
    LocalModelSpec(
        id = "pyannote-segmentation-3.0",
        displayName = "pyannote segmentation 3.0",
        kind = LocalModelKind.Diarization,
        approxBytes = 50L * 1024 * 1024,
        license = "MIT",
        notes = "Speaker-change segmentation via sherpa-onnx — packaging URL TBD",
        languages = "language-independent",
        downloadUrl = null,
    ),
    LocalModelSpec(
        id = "wespeaker-voxceleb",
        displayName = "WeSpeaker (VoxCeleb)",
        kind = LocalModelKind.Embedding,
        approxBytes = 30L * 1024 * 1024,
        license = "Apache-2.0",
        notes = "Multilingual speaker embedding candidate — packaging URL TBD",
        languages = "multilingual (evaluate)",
        downloadUrl = null,
    ),
    LocalModelSpec(
        id = "titanet-small",
        displayName = "NeMo TitaNet-small",
        kind = LocalModelKind.Embedding,
        approxBytes = 30L * 1024 * 1024,
        license = "CC-BY-4.0",
        notes = "Embedding candidate vs WeSpeaker / 3D-Speaker — packaging URL TBD",
        languages = "multilingual (evaluate)",
        downloadUrl = null,
    ),
    LocalModelSpec(
        id = "gemma-4-e4b",
        displayName = "Gemma 4 E4B",
        kind = LocalModelKind.Llm,
        approxBytes = 3650L * 1024 * 1024,
        license = "Apache-2.0",
        notes = "Default summaries on LiteRT-LM — download URL TBD",
        languages = "140+ pretrained / 35+ tuned",
        downloadUrl = null,
    ),
    LocalModelSpec(
        id = "gemma-4-e2b",
        displayName = "Gemma 4 E2B",
        kind = LocalModelKind.Llm,
        approxBytes = 2580L * 1024 * 1024,
        license = "Apache-2.0",
        notes = "Light summaries on LiteRT-LM — download URL TBD",
        languages = "140+ pretrained / 35+ tuned",
        downloadUrl = null,
    ),
    LocalModelSpec(
        id = "qwen3-4b-instruct-2507",
        displayName = "Qwen3-4B-Instruct-2507",
        kind = LocalModelKind.Llm,
        approxBytes = 2500L * 1024 * 1024,
        license = "Apache-2.0",
        notes = "llama.cpp GGUF fallback — download URL TBD",
        languages = "119 languages/dialects",
        downloadUrl = null,
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

internal fun createDownloadHttpClient(): HttpClient =
    dev.velum.ai.createAiHttpClient()

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

        update(id) { it.copy(status = LocalModelStatus.Downloading, progress = 0f, error = null) }
        emit(requireNotNull(stateFor(id)))

        val dir = storeRoot.resolve(id)
        dir.createDirectories()
        val dest = dir.resolve(current.spec.archiveFileName)
        val partial = dir.resolve("${current.spec.archiveFileName}.partial")
        if (partial.exists()) partial.deleteRecursively()

        try {
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
                    partial.appendBytes(buffer.copyOf(n))
                    written += n
                    val progress = (written.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 0.99f)
                    update(id) { it.copy(status = LocalModelStatus.Downloading, progress = progress) }
                    emit(requireNotNull(stateFor(id)))
                }
            }

            val bytes = partial.readBytes()
            val expectedSha = current.spec.sha256
            if (!expectedSha.isNullOrBlank()) {
                val actual = sha256Hex(bytes)
                if (!actual.equals(expectedSha, ignoreCase = true)) {
                    partial.deleteRecursively()
                    throw IllegalStateException("SHA-256 mismatch for $id")
                }
            }

            if (dest.exists()) dest.deleteRecursively()
            dest.writeBytes(bytes)
            partial.deleteRecursively()

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
        if (spec.downloadUrl.isNullOrBlank()) LocalModelStatus.BlockedOnUrl
        else LocalModelStatus.NotDownloaded

    private fun update(id: String, transform: (LocalModelState) -> LocalModelState) {
        _states.update { list ->
            list.map { if (it.spec.id == id) transform(it) else it }
        }
    }

}

/** Language router for local ASR — see ARCHITECTURE_AI.md. */
object LocalAsrRouter {
    private val parakeetEuropean = setOf(
        "bg", "hr", "cs", "da", "nl", "en", "et", "fi", "fr", "de", "el", "hu",
        "it", "lv", "lt", "mt", "pl", "pt", "ro", "sk", "sl", "es", "sv", "uk", "ru",
    )
    private val cjkLight = setOf("zh", "yue", "ja", "ko")

    fun chooseModelId(languageHint: String?, mixedAudio: Boolean = false): String {
        if (mixedAudio) return "whisper-large-v3-turbo"
        val lang = languageHint?.lowercase()?.substringBefore('-')?.takeIf { it != "auto" }
        return when {
            lang == null -> "parakeet-tdt-0.6b-v3-int8"
            lang in parakeetEuropean -> "parakeet-tdt-0.6b-v3-int8"
            lang in cjkLight -> "sensevoice-small"
            else -> "whisper-large-v3-turbo"
        }
    }
}
