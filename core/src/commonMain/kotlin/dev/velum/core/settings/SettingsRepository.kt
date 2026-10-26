package dev.velum.core.settings

import dev.velum.core.model.AiModelInfo
import dev.velum.core.model.AiProviderSettings
import dev.velum.core.security.SecureKeys
import dev.velum.core.security.SecureStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SettingsRepository(
    private val store: SecureStore,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val _settings = MutableStateFlow(AiProviderSettings())
    val settings: StateFlow<AiProviderSettings> = _settings.asStateFlow()

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
        )
    }

    suspend fun setBaseUrl(url: String) {
        val normalized = url.trim().trimEnd('/')
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

    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        private const val KEY_BASE_URL = "ai.base_url"
        private const val KEY_TRANSCRIPTION_MODEL = "ai.transcription_model"
        private const val KEY_CHAT_MODEL = "ai.chat_model"
        private const val KEY_CACHED_MODELS = "ai.cached_models_json"
    }
}
