package dev.velum.core.settings

import dev.velum.core.model.AiModelInfo
import dev.velum.core.model.AiProviderSettings
import dev.velum.core.model.ComputeMode
import dev.velum.core.security.SecureKeys
import dev.velum.core.security.SecureStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class AppPreferences(
    val cleanTranscript: Boolean = true,
    val autoSpeakerLabel: Boolean = false,
    val displayName: String = "",
    val contentFocus: String = "",
    val customInstructions: String = "",
    val useSavedMemory: Boolean = false,
    /** Global AutoFlow notification preference mirrored from UI toggle. */
    val autoflowNotify: Boolean = true,
    /** `system` | `light` | `dark` */
    val themeMode: String = "system",
    /** `system` | `en` | `pt-BR` */
    val appLanguage: String = "system",
    /** Default phone-mic [MicInputSource] name when starting a new capture. */
    val defaultMicInput: String = "Mic",
    /** Prefer SoftAP Wi‑Fi fast path when the paired device supports it. */
    val preferWifiFastPath: Boolean = true,
    /** Re-scan watched import folders when the app starts. */
    val autoSyncWatchedFolders: Boolean = true,
    /** Delete local audio after a successful transcription/summary job. */
    val deleteAudioAfterProcessing: Boolean = false,
)

class SettingsRepository(
    private val store: SecureStore,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val _settings = MutableStateFlow(AiProviderSettings())
    val settings: StateFlow<AiProviderSettings> = _settings.asStateFlow()

    private val _preferences = MutableStateFlow(AppPreferences())
    val preferences: StateFlow<AppPreferences> = _preferences.asStateFlow()

    suspend fun load() {
        val baseUrl = store.getString(KEY_BASE_URL) ?: DEFAULT_BASE_URL
        val hasKey = store.contains(SecureKeys.AI_API_KEY)
        val transcription = store.getString(KEY_TRANSCRIPTION_MODEL)
        val chat = store.getString(KEY_CHAT_MODEL)
        val cached = store.getString(KEY_CACHED_MODELS)?.let {
            runCatching { json.decodeFromString<List<AiModelInfo>>(it) }.getOrDefault(emptyList())
        } ?: emptyList()
        _settings.value = AiProviderSettings(
            baseUrl = baseUrl,
            hasApiKey = hasKey,
            transcriptionModel = transcription,
            chatModel = chat,
            cachedModels = cached,
            transcriptionMode = readMode(KEY_TRANSCRIPTION_MODE, ComputeMode.Local),
            diarizationMode = readMode(KEY_DIARIZATION_MODE, ComputeMode.Local),
            summaryMode = readMode(KEY_SUMMARY_MODE, ComputeMode.Local),
            audioLanguage = store.getString(KEY_AUDIO_LANGUAGE) ?: "auto",
            outputLanguage = store.getString(KEY_OUTPUT_LANGUAGE) ?: "auto",
            localAsrModelId = store.getString(KEY_LOCAL_ASR_MODEL),
            localDiarizationModelId = store.getString(KEY_LOCAL_DIARIZATION_MODEL),
            localLlmModelId = store.getString(KEY_LOCAL_LLM_MODEL),
            asrSizeTier = store.getString(KEY_ASR_SIZE_TIER) ?: "auto",
            speakerMatchThreshold = store.getString(KEY_SPEAKER_THRESHOLD)
                ?.toFloatOrNull()
                ?.coerceIn(0.3f, 0.95f)
                ?: 0.55f,
        )
        _preferences.value = AppPreferences(
            cleanTranscript = store.getString(KEY_CLEAN_TRANSCRIPT)?.toBooleanStrictOrNull() ?: true,
            autoSpeakerLabel = store.getString(KEY_AUTO_SPEAKER)?.toBooleanStrictOrNull() ?: false,
            displayName = store.getString(KEY_DISPLAY_NAME).orEmpty(),
            contentFocus = store.getString(KEY_CONTENT_FOCUS).orEmpty(),
            customInstructions = store.getString(KEY_CUSTOM_INSTRUCTIONS).orEmpty(),
            useSavedMemory = store.getString(KEY_USE_MEMORY)?.toBooleanStrictOrNull() ?: false,
            autoflowNotify = store.getString(KEY_AUTOFLOW_NOTIFY)?.toBooleanStrictOrNull() ?: true,
            themeMode = store.getString(KEY_THEME_MODE)?.takeIf { it in THEME_MODES } ?: "system",
            appLanguage = store.getString(KEY_APP_LANGUAGE)?.takeIf { it in APP_LANGUAGES } ?: "system",
            defaultMicInput = store.getString(KEY_DEFAULT_MIC_INPUT) ?: "Mic",
            preferWifiFastPath = store.getString(KEY_PREFER_WIFI)?.toBooleanStrictOrNull() ?: true,
            autoSyncWatchedFolders =
                store.getString(KEY_AUTO_SYNC_WATCHED)?.toBooleanStrictOrNull() ?: true,
            deleteAudioAfterProcessing =
                store.getString(KEY_DELETE_AUDIO_AFTER)?.toBooleanStrictOrNull() ?: false,
        )
    }

    suspend fun setBaseUrl(url: String) {
        val normalized = AiBaseUrl.normalizeAndValidate(url)
        store.putString(KEY_BASE_URL, normalized)
        _settings.update { it.copy(baseUrl = normalized) }
    }

    suspend fun setApiKey(apiKey: String) {
        val trimmed = apiKey.trim()
        if (trimmed.isEmpty()) {
            store.remove(SecureKeys.AI_API_KEY)
            _settings.update { it.copy(hasApiKey = false) }
        } else {
            store.putString(SecureKeys.AI_API_KEY, trimmed)
            _settings.update { it.copy(hasApiKey = true) }
        }
    }

    suspend fun getApiKey(): String? = store.getString(SecureKeys.AI_API_KEY)

    suspend fun setModels(models: List<AiModelInfo>) {
        store.putString(KEY_CACHED_MODELS, json.encodeToString(models))
        _settings.update { it.copy(cachedModels = models) }
    }

    suspend fun setTranscriptionModel(modelId: String?) {
        if (modelId == null) store.remove(KEY_TRANSCRIPTION_MODEL)
        else store.putString(KEY_TRANSCRIPTION_MODEL, modelId)
        _settings.update { it.copy(transcriptionModel = modelId) }
    }

    suspend fun setChatModel(modelId: String?) {
        if (modelId == null) store.remove(KEY_CHAT_MODEL)
        else store.putString(KEY_CHAT_MODEL, modelId)
        _settings.update { it.copy(chatModel = modelId) }
    }

    suspend fun setTranscriptionMode(mode: ComputeMode) {
        store.putString(KEY_TRANSCRIPTION_MODE, mode.name)
        _settings.update { it.copy(transcriptionMode = mode) }
    }

    suspend fun setDiarizationMode(mode: ComputeMode) {
        store.putString(KEY_DIARIZATION_MODE, mode.name)
        _settings.update { it.copy(diarizationMode = mode) }
    }

    suspend fun setSummaryMode(mode: ComputeMode) {
        store.putString(KEY_SUMMARY_MODE, mode.name)
        _settings.update { it.copy(summaryMode = mode) }
    }

    suspend fun setAudioLanguage(code: String) {
        val value = code.trim().ifBlank { "auto" }
        store.putString(KEY_AUDIO_LANGUAGE, value)
        _settings.update { it.copy(audioLanguage = value) }
    }

    suspend fun setOutputLanguage(code: String) {
        val value = code.trim().ifBlank { "auto" }
        store.putString(KEY_OUTPUT_LANGUAGE, value)
        _settings.update { it.copy(outputLanguage = value) }
    }

    suspend fun setLocalAsrModelId(id: String?) {
        writeOptional(KEY_LOCAL_ASR_MODEL, id)
        _settings.update { it.copy(localAsrModelId = id) }
    }

    suspend fun setLocalDiarizationModelId(id: String?) {
        writeOptional(KEY_LOCAL_DIARIZATION_MODEL, id)
        _settings.update { it.copy(localDiarizationModelId = id) }
    }

    suspend fun setLocalLlmModelId(id: String?) {
        writeOptional(KEY_LOCAL_LLM_MODEL, id)
        _settings.update { it.copy(localLlmModelId = id) }
    }

    suspend fun setAsrSizeTier(tier: String) {
        val value = when (tier.lowercase()) {
            "tiny", "standard", "quality" -> tier.lowercase()
            else -> "auto"
        }
        store.putString(KEY_ASR_SIZE_TIER, value)
        _settings.update { it.copy(asrSizeTier = value) }
        // Tiny clears the pin so LocalAsrRouter can pick Whistle vs Whisper Tiny by language.
        // Standard/Quality still pin a concrete catalog id; Auto clears the pin entirely.
        when (value) {
            "auto", "tiny" -> setLocalAsrModelId(null)
            "quality" -> setLocalAsrModelId("whisper-large-v3-turbo")
            else -> setLocalAsrModelId("parakeet-tdt-0.6b-v3-int8")
        }
    }

    suspend fun setSpeakerMatchThreshold(threshold: Float) {
        val value = threshold.coerceIn(0.3f, 0.95f)
        store.putString(KEY_SPEAKER_THRESHOLD, value.toString())
        _settings.update { it.copy(speakerMatchThreshold = value) }
    }

    suspend fun setCleanTranscript(enabled: Boolean) {
        store.putString(KEY_CLEAN_TRANSCRIPT, enabled.toString())
        _preferences.update { it.copy(cleanTranscript = enabled) }
    }

    suspend fun setAutoSpeakerLabel(enabled: Boolean) {
        store.putString(KEY_AUTO_SPEAKER, enabled.toString())
        _preferences.update { it.copy(autoSpeakerLabel = enabled) }
    }

    suspend fun setDisplayName(name: String) {
        store.putString(KEY_DISPLAY_NAME, name)
        _preferences.update { it.copy(displayName = name) }
    }

    suspend fun setContentFocus(text: String) {
        store.putString(KEY_CONTENT_FOCUS, text)
        _preferences.update { it.copy(contentFocus = text) }
    }

    suspend fun setCustomInstructions(text: String) {
        store.putString(KEY_CUSTOM_INSTRUCTIONS, text)
        _preferences.update { it.copy(customInstructions = text) }
    }

    suspend fun setUseSavedMemory(enabled: Boolean) {
        store.putString(KEY_USE_MEMORY, enabled.toString())
        _preferences.update { it.copy(useSavedMemory = enabled) }
    }

    suspend fun setAutoflowNotify(enabled: Boolean) {
        store.putString(KEY_AUTOFLOW_NOTIFY, enabled.toString())
        _preferences.update { it.copy(autoflowNotify = enabled) }
    }

    suspend fun setThemeMode(mode: String) {
        val value = mode.takeIf { it in THEME_MODES } ?: "system"
        store.putString(KEY_THEME_MODE, value)
        _preferences.update { it.copy(themeMode = value) }
    }

    suspend fun setAppLanguage(tag: String) {
        val value = tag.takeIf { it in APP_LANGUAGES } ?: "system"
        store.putString(KEY_APP_LANGUAGE, value)
        _preferences.update { it.copy(appLanguage = value) }
    }

    suspend fun setDefaultMicInput(source: String) {
        val value = source.trim().ifBlank { "Mic" }
        store.putString(KEY_DEFAULT_MIC_INPUT, value)
        _preferences.update { it.copy(defaultMicInput = value) }
    }

    suspend fun setPreferWifiFastPath(enabled: Boolean) {
        store.putString(KEY_PREFER_WIFI, enabled.toString())
        _preferences.update { it.copy(preferWifiFastPath = enabled) }
    }

    suspend fun setAutoSyncWatchedFolders(enabled: Boolean) {
        store.putString(KEY_AUTO_SYNC_WATCHED, enabled.toString())
        _preferences.update { it.copy(autoSyncWatchedFolders = enabled) }
    }

    suspend fun setDeleteAudioAfterProcessing(enabled: Boolean) {
        store.putString(KEY_DELETE_AUDIO_AFTER, enabled.toString())
        _preferences.update { it.copy(deleteAudioAfterProcessing = enabled) }
    }

    private suspend fun writeOptional(key: String, value: String?) {
        if (value.isNullOrBlank()) store.remove(key) else store.putString(key, value)
    }

    private suspend fun readMode(key: String, default: ComputeMode): ComputeMode {
        val raw = store.getString(key) ?: return default
        return runCatching { ComputeMode.valueOf(raw) }.getOrDefault(default)
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        private const val KEY_BASE_URL = "ai.base_url"
        private const val KEY_TRANSCRIPTION_MODEL = "ai.transcription_model"
        private const val KEY_CHAT_MODEL = "ai.chat_model"
        private const val KEY_CACHED_MODELS = "ai.cached_models_json"
        private const val KEY_TRANSCRIPTION_MODE = "ai.transcription_mode"
        private const val KEY_DIARIZATION_MODE = "ai.diarization_mode"
        private const val KEY_SUMMARY_MODE = "ai.summary_mode"
        private const val KEY_AUDIO_LANGUAGE = "ai.audio_language"
        private const val KEY_OUTPUT_LANGUAGE = "ai.output_language"
        private const val KEY_LOCAL_ASR_MODEL = "ai.local_asr_model"
        private const val KEY_LOCAL_DIARIZATION_MODEL = "ai.local_diarization_model"
        private const val KEY_LOCAL_LLM_MODEL = "ai.local_llm_model"
        private const val KEY_ASR_SIZE_TIER = "ai.asr_size_tier"
        private const val KEY_SPEAKER_THRESHOLD = "ai.speaker_match_threshold"
        private const val KEY_CLEAN_TRANSCRIPT = "prefs.clean_transcript"
        private const val KEY_AUTO_SPEAKER = "prefs.auto_speaker_label"
        private const val KEY_DISPLAY_NAME = "prefs.display_name"
        private const val KEY_CONTENT_FOCUS = "prefs.content_focus"
        private const val KEY_CUSTOM_INSTRUCTIONS = "prefs.custom_instructions"
        private const val KEY_USE_MEMORY = "prefs.use_saved_memory"
        private const val KEY_AUTOFLOW_NOTIFY = "prefs.autoflow_notify"
        private const val KEY_THEME_MODE = "prefs.theme_mode"
        private const val KEY_APP_LANGUAGE = "prefs.app_language"
        private const val KEY_DEFAULT_MIC_INPUT = "prefs.default_mic_input"
        private const val KEY_PREFER_WIFI = "prefs.prefer_wifi_fast_path"
        private const val KEY_AUTO_SYNC_WATCHED = "prefs.auto_sync_watched_folders"
        private const val KEY_DELETE_AUDIO_AFTER = "prefs.delete_audio_after_processing"
        private val THEME_MODES = setOf("system", "light", "dark")
        private val APP_LANGUAGES = setOf("system", "en", "pt-BR")
    }
}
