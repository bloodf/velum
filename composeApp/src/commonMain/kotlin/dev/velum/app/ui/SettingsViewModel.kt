package dev.velum.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.velum.ai.AiApiException
import dev.velum.ai.MissingApiKeyException
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
    /** HTTP status from the last /models failure, when known. */
    val errorStatus: Int? = null,
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
        viewModelScope.launch {
            try {
                settingsRepository.setBaseUrl(url)
                clearMessage()
            } catch (t: Throwable) {
                _ui.value = SettingsUiState(
                    error = t.message ?: t.toString(),
                    errorStatus = null,
                )
            }
        }
    }

    fun setApiKey(key: String) {
        // Never log the key — only store via Keystore-backed SecureStore.
        viewModelScope.launch { settingsRepository.setApiKey(key) }
    }

    fun selectTranscriptionModel(id: String) {
        viewModelScope.launch { settingsRepository.setTranscriptionModel(id) }
    }

    fun selectDiarizationModel(id: String) {
        viewModelScope.launch { settingsRepository.setDiarizationModel(id) }
    }

    fun selectChatModel(id: String) {
        viewModelScope.launch { settingsRepository.setChatModel(id) }
    }

    fun selectEmbeddingModel(id: String) {
        viewModelScope.launch { settingsRepository.setEmbeddingModel(id) }
    }

    fun refreshModels() {
        viewModelScope.launch {
            _ui.value = SettingsUiState(refreshing = true)
            try {
                val key = settingsRepository.getApiKey()?.takeIf { it.isNotBlank() }
                    ?: throw MissingApiKeyException(
                        "API key required to refresh models. Add your OpenAI-compatible key first.",
                    )
                val base = settings.value.baseUrl
                val models = aiClient.listModels(base, key)
                settingsRepository.setModels(models)
                _ui.value = SettingsUiState(
                    refreshing = false,
                    message = "loaded:${models.size}",
                )
            } catch (t: MissingApiKeyException) {
                _ui.value = SettingsUiState(
                    refreshing = false,
                    error = t.message,
                )
            } catch (t: AiApiException) {
                _ui.value = SettingsUiState(
                    refreshing = false,
                    error = t.message,
                    errorStatus = t.status,
                )
            } catch (t: IllegalArgumentException) {
                _ui.value = SettingsUiState(
                    refreshing = false,
                    error = t.message ?: t.toString(),
                    errorStatus = -1,
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
        _ui.value = _ui.value.copy(message = null, error = null, errorStatus = null)
    }
}
