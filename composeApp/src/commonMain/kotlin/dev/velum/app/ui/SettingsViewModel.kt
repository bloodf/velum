package dev.velum.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.velum.ai.AiApiException
import dev.velum.ai.OpenAiCompatibleClient
import dev.velum.core.model.AiProviderSettings
import dev.velum.core.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val refreshing: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val aiClient: OpenAiCompatibleClient,
) : ViewModel() {
    val settings: StateFlow<AiProviderSettings> =
        settingsRepository.settings.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            AiProviderSettings(),
        )

    private val _ui = MutableStateFlow(SettingsUiState())
    val ui: StateFlow<SettingsUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch { settingsRepository.load() }
    }

    fun setBaseUrl(url: String) {
        viewModelScope.launch { settingsRepository.setBaseUrl(url) }
    }

    fun setApiKey(key: String) {
        viewModelScope.launch { settingsRepository.setApiKey(key) }
    }

    fun selectTranscriptionModel(id: String) {
        viewModelScope.launch { settingsRepository.setTranscriptionModel(id) }
    }

    fun selectChatModel(id: String) {
        viewModelScope.launch { settingsRepository.setChatModel(id) }
    }

    fun refreshModels() {
        viewModelScope.launch {
            _ui.value = SettingsUiState(refreshing = true)
            try {
                val key = settingsRepository.getApiKey()
                    ?: throw AiApiException(401, "Add an API key first")
                val base = settings.value.baseUrl
                val models = aiClient.listModels(base, key)
                settingsRepository.setModels(models)
                _ui.value = SettingsUiState(
                    refreshing = false,
                    message = "Loaded ${models.size} models",
                )
            } catch (t: Throwable) {
                _ui.value = SettingsUiState(
                    refreshing = false,
                    error = t.message ?: t.toString(),
                )
            }
        }
    }

    fun clearMessage() {
        _ui.value = _ui.value.copy(message = null, error = null)
    }
}
