package dev.velum.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.velum.ai.AiApiException
import dev.velum.ai.MissingApiKeyException
import dev.velum.ai.OpenAiCompatibleClient
import dev.velum.core.model.AiProviderSettings
import dev.velum.core.settings.AiApiTask
import dev.velum.core.settings.ApiEndpointProfile
import dev.velum.core.settings.ApiModelResolver
import dev.velum.core.settings.SettingsRepository
import dev.velum.core.settings.TaskModelBinding
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

    fun setProfileApiKey(profileId: String, key: String) {
        viewModelScope.launch { settingsRepository.setProfileApiKey(profileId, key) }
    }

    fun upsertProfile(profile: ApiEndpointProfile) {
        viewModelScope.launch {
            try {
                settingsRepository.upsertProfile(profile)
                clearMessage()
            } catch (t: Throwable) {
                _ui.value = SettingsUiState(error = t.message ?: t.toString())
            }
        }
    }

    fun deleteProfile(profileId: String) {
        viewModelScope.launch { settingsRepository.deleteProfile(profileId) }
    }

    fun setActiveProfile(profileId: String) {
        viewModelScope.launch { settingsRepository.setActiveProfile(profileId) }
    }

    fun setTaskBinding(task: AiApiTask, binding: TaskModelBinding) {
        viewModelScope.launch { settingsRepository.setTaskBinding(task, binding) }
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

    fun refreshModels(profileId: String? = null) {
        viewModelScope.launch {
            _ui.value = SettingsUiState(refreshing = true)
            try {
                val cfg = settings.value
                val id = profileId
                    ?: cfg.activeProfileId
                    ?: cfg.profiles.firstOrNull()?.id
                    ?: ApiModelResolver.DEFAULT_PROFILE_ID
                val profile = cfg.profiles.firstOrNull { it.id == id }
                    ?: ApiEndpointProfile(
                        id = id,
                        name = ApiModelResolver.DEFAULT_PROFILE_NAME,
                        baseUrl = cfg.baseUrl,
                    )
                val key = settingsRepository.getProfileApiKey(profile.id)?.takeIf { it.isNotBlank() }
                    ?: throw MissingApiKeyException(
                        "API key required to refresh models. Add your OpenAI-compatible key first.",
                    )
                val models = aiClient.listModels(profile.baseUrl, key)
                settingsRepository.setProfileModels(profile.id, models)
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
                    error = "bad_url",
                    errorStatus = -1,
                )
            } catch (_: Throwable) {
                _ui.value = SettingsUiState(
                    refreshing = false,
                    error = "generic",
                )
            }
        }
    }

    fun clearMessage() {
        _ui.value = _ui.value.copy(message = null, error = null, errorStatus = null)
    }
}
