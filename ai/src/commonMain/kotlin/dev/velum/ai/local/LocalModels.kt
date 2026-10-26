package dev.velum.ai.local

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
)

val DefaultLocalCatalog: List<LocalModelSpec> = listOf(
    LocalModelSpec(
        id = "parakeet-tdt-0.6b-v3-int8",
        displayName = "Parakeet TDT 0.6B v3",
        kind = LocalModelKind.Asr,
        approxBytes = 640L * 1024 * 1024,
        license = "CC-BY-4.0",
        notes = "Default for 25 European languages via sherpa-onnx",
        languages = "25 European (incl. pt, en, es, fr, de, …)",
    ),
    LocalModelSpec(
        id = "whisper-large-v3-turbo",
        displayName = "Whisper large-v3-turbo",
        kind = LocalModelKind.Asr,
        approxBytes = 800L * 1024 * 1024,
        license = "MIT",
        notes = "Fallback for non-EU languages and mixed audio",
        languages = "~99 (quality varies)",
    ),
    LocalModelSpec(
        id = "sensevoice-small",
        displayName = "SenseVoice Small",
        kind = LocalModelKind.Asr,
        approxBytes = 250L * 1024 * 1024,
        license = "FunASR (check)",
        notes = "Light CJK option (zh / yue / ja / ko)",
        languages = "zh, yue, en, ja, ko",
    ),
    LocalModelSpec(
        id = "pyannote-segmentation-3.0",
        displayName = "pyannote segmentation 3.0",
        kind = LocalModelKind.Diarization,
        approxBytes = 50L * 1024 * 1024,
        license = "MIT",
        notes = "Speaker-change segmentation via sherpa-onnx",
        languages = "language-independent",
    ),
    LocalModelSpec(
        id = "wespeaker-voxceleb",
        displayName = "WeSpeaker (VoxCeleb)",
        kind = LocalModelKind.Embedding,
        approxBytes = 30L * 1024 * 1024,
        license = "Apache-2.0",
        notes = "Multilingual speaker embedding candidate",
        languages = "multilingual (evaluate)",
    ),
    LocalModelSpec(
        id = "titanet-small",
        displayName = "NeMo TitaNet-small",
        kind = LocalModelKind.Embedding,
        approxBytes = 30L * 1024 * 1024,
        license = "CC-BY-4.0",
        notes = "Embedding candidate vs WeSpeaker / 3D-Speaker",
        languages = "multilingual (evaluate)",
    ),
    LocalModelSpec(
        id = "gemma-4-e4b",
        displayName = "Gemma 4 E4B",
        kind = LocalModelKind.Llm,
        approxBytes = 3650L * 1024 * 1024,
        license = "Apache-2.0",
        notes = "Default summaries on LiteRT-LM",
        languages = "140+ pretrained / 35+ tuned",
    ),
    LocalModelSpec(
        id = "gemma-4-e2b",
        displayName = "Gemma 4 E2B",
        kind = LocalModelKind.Llm,
        approxBytes = 2580L * 1024 * 1024,
        license = "Apache-2.0",
        notes = "Light summaries on LiteRT-LM",
        languages = "140+ pretrained / 35+ tuned",
    ),
    LocalModelSpec(
        id = "qwen3-4b-instruct-2507",
        displayName = "Qwen3-4B-Instruct-2507",
        kind = LocalModelKind.Llm,
        approxBytes = 2500L * 1024 * 1024,
        license = "Apache-2.0",
        notes = "llama.cpp GGUF fallback",
        languages = "119 languages/dialects",
    ),
)

data class LocalModelState(
    val spec: LocalModelSpec,
    val status: LocalModelStatus = LocalModelStatus.NotDownloaded,
    val progress: Float = 0f,
    val error: String? = null,
)

/**
 * Stub model manager. Real downloads / WorkManager / thermal checks land later.
 * Never persists weights into the repo.
 */
class LocalModelManager(
    catalog: List<LocalModelSpec> = DefaultLocalCatalog,
) {
    private val _states = MutableStateFlow(
        catalog.map { LocalModelState(it) },
    )
    val states: StateFlow<List<LocalModelState>> = _states.asStateFlow()

    fun stateFor(id: String): LocalModelState? = _states.value.firstOrNull { it.spec.id == id }

    /** Simulated download — no network, no files written. */
    fun download(id: String): Flow<LocalModelState> = flow {
        update(id) { it.copy(status = LocalModelStatus.Downloading, progress = 0f, error = null) }
        emit(requireNotNull(stateFor(id)))
        update(id) { it.copy(progress = 0.5f) }
        emit(requireNotNull(stateFor(id)))
        update(id) { it.copy(status = LocalModelStatus.Ready, progress = 1f) }
        emit(requireNotNull(stateFor(id)))
    }

    fun delete(id: String) {
        update(id) {
            it.copy(status = LocalModelStatus.NotDownloaded, progress = 0f, error = null)
        }
    }

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
            lang == null -> "parakeet-tdt-0.6b-v3-int8" // will re-route after LID
            lang in parakeetEuropean -> "parakeet-tdt-0.6b-v3-int8"
            lang in cjkLight -> "sensevoice-small"
            else -> "whisper-large-v3-turbo"
        }
    }
}
