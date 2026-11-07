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
import dev.velum.core.security.SecureStoreUnavailableException
import kotlinx.coroutines.CancellationException
import dev.velum.core.settings.TaskModelBinding
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val settings: StateFlow<AiProviderSettings> = settingsRepository.settings

    private val _ui = MutableStateFlow(SettingsUiState())
    val ui: StateFlow<SettingsUiState> = _ui.asStateFlow()

    init { reloadSettings() }

    fun reloadSettings() = storageAction { settingsRepository.load() }

    private fun storageAction(action: suspend () -> Unit) = viewModelScope.launch {
        try {
            action()
            clearMessage()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecureStoreUnavailableException) {
            storageFailed()
        } catch (_: Exception) {
            _ui.value = SettingsUiState(error = "generic")
        }
    }

    /** UI settings actions share a non-destructive failure path; unrelated bugs still propagate. */
    fun runStorageAction(action: suspend () -> Unit) = viewModelScope.launch { handleStorageFailure(action) }

    suspend fun handleStorageFailure(action: suspend () -> Unit): Boolean = try {
        action()
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SecureStoreUnavailableException) {
        storageFailed()
        false
    }

    private fun storageFailed() {
        settingsRepository.reportStorageUnavailable()
        _ui.value = SettingsUiState(error = "storage_unavailable")
    }

    fun setBaseUrl(url: String) { storageAction { settingsRepository.setBaseUrl(url) } }
    fun setApiKey(key: String) { storageAction { settingsRepository.setApiKey(key) } }
    fun setProfileApiKey(profileId: String, key: String) { storageAction { settingsRepository.setProfileApiKey(profileId, key) } }
    fun createProfile(onCreated: (String) -> Unit) { storageAction { onCreated(settingsRepository.createProfile().id) } }
    fun upsertProfile(profile: ApiEndpointProfile) { storageAction { settingsRepository.upsertProfile(profile) } }
    fun deleteProfile(profileId: String) { storageAction { settingsRepository.deleteProfile(profileId) } }
    fun setActiveProfile(profileId: String) { storageAction { settingsRepository.setActiveProfile(profileId) } }
    fun setTaskBinding(task: AiApiTask, binding: TaskModelBinding) { storageAction { settingsRepository.setTaskBinding(task, binding) } }
    fun selectTranscriptionModel(id: String) { storageAction { settingsRepository.setTranscriptionModel(id) } }
    fun selectDiarizationModel(id: String) { storageAction { settingsRepository.setDiarizationModel(id) } }
    fun selectChatModel(id: String) { storageAction { settingsRepository.setChatModel(id) } }
    fun selectEmbeddingModel(id: String) { storageAction { settingsRepository.setEmbeddingModel(id) } }

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
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SecureStoreUnavailableException) {
                storageFailed()
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
            } catch (_: Exception) {
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
