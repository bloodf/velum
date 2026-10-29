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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus

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
    private val _ui = MutableStateFlow(SettingsUiState())
    val ui: StateFlow<SettingsUiState> = _ui.asStateFlow()

    /**
     * Last-resort handler so a failed SecureStore write never becomes an
     * uncaught StandaloneCoroutine crash on Main (see #92 launch smoke).
     * Fixed error token only — never [Throwable.message] (may echo secrets).
     */
    private val secureWriteHandler = CoroutineExceptionHandler { _, t ->
        if (t is CancellationException) return@CoroutineExceptionHandler
        _ui.value = SettingsUiState(error = ERR_SECURE_STORE)
    }

    private val safeScope = viewModelScope + secureWriteHandler

    val settings: StateFlow<AiProviderSettings> =
        settingsRepository.settings.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            AiProviderSettings(),
        )

    init {
        // First-run load persists default endpoint profiles via putString.
        // Must not crash the process when Keystore encrypt fails.
        safeScope.launch {
            try {
                settingsRepository.load()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _ui.value = SettingsUiState(error = ERR_SECURE_STORE)
            }
        }
    }

    fun setBaseUrl(url: String) {
        safeScope.launch {
            try {
                settingsRepository.setBaseUrl(url)
                clearMessage()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _ui.value = SettingsUiState(error = ERR_SECURE_STORE)
            }
        }
    }

    fun setApiKey(key: String) {
        // Never log the key — only store via Keystore-backed SecureStore.
        safeScope.launch {
            try {
                settingsRepository.setApiKey(key)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _ui.value = SettingsUiState(error = ERR_SECURE_STORE)
            }
        }
    }

    fun setProfileApiKey(profileId: String, key: String) {
        safeScope.launch {
            try {
                settingsRepository.setProfileApiKey(profileId, key)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _ui.value = SettingsUiState(error = ERR_SECURE_STORE)
            }
        }
    }

    fun upsertProfile(profile: ApiEndpointProfile) {
        safeScope.launch {
            try {
                settingsRepository.upsertProfile(profile)
                clearMessage()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _ui.value = SettingsUiState(error = ERR_SECURE_STORE)
            }
        }
    }

    fun deleteProfile(profileId: String) {
        safeScope.launch {
            try {
                settingsRepository.deleteProfile(profileId)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _ui.value = SettingsUiState(error = ERR_SECURE_STORE)
            }
        }
    }

    fun setActiveProfile(profileId: String) {
        safeScope.launch {
            try {
                settingsRepository.setActiveProfile(profileId)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _ui.value = SettingsUiState(error = ERR_SECURE_STORE)
            }
        }
    }

    fun setTaskBinding(task: AiApiTask, binding: TaskModelBinding) {
        safeScope.launch {
            try {
                settingsRepository.setTaskBinding(task, binding)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _ui.value = SettingsUiState(error = ERR_SECURE_STORE)
            }
        }
    }

    fun selectTranscriptionModel(id: String) {
        safeScope.launch {
            try {
                settingsRepository.setTranscriptionModel(id)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _ui.value = SettingsUiState(error = ERR_SECURE_STORE)
            }
        }
    }

    fun selectDiarizationModel(id: String) {
        safeScope.launch {
            try {
                settingsRepository.setDiarizationModel(id)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _ui.value = SettingsUiState(error = ERR_SECURE_STORE)
            }
        }
    }

    fun selectChatModel(id: String) {
        safeScope.launch {
            try {
                settingsRepository.setChatModel(id)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _ui.value = SettingsUiState(error = ERR_SECURE_STORE)
            }
        }
    }

    fun selectEmbeddingModel(id: String) {
        safeScope.launch {
            try {
                settingsRepository.setEmbeddingModel(id)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _ui.value = SettingsUiState(error = ERR_SECURE_STORE)
            }
        }
    }

    fun refreshModels(profileId: String? = null) {
        safeScope.launch {
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
            } catch (_: IllegalArgumentException) {
                _ui.value = SettingsUiState(
                    refreshing = false,
                    error = "bad_url",
                    errorStatus = -1,
                )
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
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

    companion object {
        /** Fixed UI token — never surface raw Keystore / SecureStore exception text. */
        const val ERR_SECURE_STORE = "secure_store_unavailable"
    }
}
