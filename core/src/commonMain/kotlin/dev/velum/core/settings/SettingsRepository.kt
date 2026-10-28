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
        val profiles = loadOrMigrateProfiles()
        val activeProfileId = store.getString(KEY_ACTIVE_PROFILE)
            ?.takeIf { id -> profiles.any { it.id == id } }
            ?: profiles.firstOrNull()?.id
            ?: ApiModelResolver.DEFAULT_PROFILE_ID
        val activeProfile = profiles.firstOrNull { it.id == activeProfileId } ?: profiles.first()

        val taskBindings = loadTaskBindings()
        val cachedByProfile = loadCachedModelsByProfile(profiles)
        val legacyCached = store.getString(KEY_CACHED_MODELS)?.let {
            runCatching { json.decodeFromString<List<AiModelInfo>>(it) }.getOrDefault(emptyList())
        } ?: emptyList()
        val activeCached = cachedByProfile[activeProfile.id]
            ?.takeIf { it.isNotEmpty() }
            ?: legacyCached

        val transcription = bindingModel(taskBindings, AiApiTask.Transcription)
            ?: store.getString(KEY_TRANSCRIPTION_MODEL)
        val diarization = bindingModel(taskBindings, AiApiTask.Diarization)
            ?: store.getString(KEY_DIARIZATION_MODEL)
        val chat = bindingModel(taskBindings, AiApiTask.Summary)
            ?: store.getString(KEY_CHAT_MODEL)
        val embedding = store.getString(KEY_EMBEDDING_MODEL)
        val hasKey = store.contains(SecureKeys.apiKey(activeProfile.id)) ||
            (activeProfile.id == ApiModelResolver.DEFAULT_PROFILE_ID &&
                store.contains(SecureKeys.AI_API_KEY))

        _settings.value = AiProviderSettings(
            baseUrl = activeProfile.baseUrl,
            hasApiKey = hasKey,
            transcriptionModel = transcription,
            diarizationModel = diarization,
            chatModel = chat,
            embeddingModel = embedding,
            cachedModels = activeCached,
            profiles = profiles,
            taskBindings = taskBindings,
            activeProfileId = activeProfile.id,
            cachedModelsByProfile = cachedByProfile,
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
        val profileId = defaultOrActiveProfileId()
        upsertProfile(
            ApiEndpointProfile(
                id = profileId,
                name = profileName(profileId),
                baseUrl = normalized,
            ),
        )
        // Legacy mirror for default profile.
        if (profileId == ApiModelResolver.DEFAULT_PROFILE_ID) {
            store.putString(KEY_BASE_URL, normalized)
        }
    }

    suspend fun setApiKey(apiKey: String) {
        setProfileApiKey(defaultOrActiveProfileId(), apiKey)
    }

    suspend fun getApiKey(): String? = getProfileApiKey(defaultOrActiveProfileId())

    suspend fun setProfileApiKey(profileId: String, apiKey: String) {
        val trimmed = apiKey.trim()
        val keyName = SecureKeys.apiKey(profileId)
        if (trimmed.isEmpty()) {
            store.remove(keyName)
            if (profileId == ApiModelResolver.DEFAULT_PROFILE_ID) {
                store.remove(SecureKeys.AI_API_KEY)
            }
        } else {
            store.putString(keyName, trimmed)
            if (profileId == ApiModelResolver.DEFAULT_PROFILE_ID) {
                store.putString(SecureKeys.AI_API_KEY, trimmed)
            }
        }
        refreshHasApiKeyFlags()
    }

    suspend fun getProfileApiKey(profileId: String): String? {
        store.getString(SecureKeys.apiKey(profileId))?.let { return it }
        if (profileId == ApiModelResolver.DEFAULT_PROFILE_ID) {
            return store.getString(SecureKeys.AI_API_KEY)
        }
        return null
    }

    suspend fun upsertProfile(profile: ApiEndpointProfile) {
        val normalized = profile.copy(baseUrl = AiBaseUrl.normalizeAndValidate(profile.baseUrl))
        val current = _settings.value.profiles.toMutableList()
        val idx = current.indexOfFirst { it.id == normalized.id }
        if (idx >= 0) current[idx] = normalized else current.add(normalized)
        persistProfiles(current)
        if (normalized.id == ApiModelResolver.DEFAULT_PROFILE_ID) {
            store.putString(KEY_BASE_URL, normalized.baseUrl)
        }
        val activeId = _settings.value.activeProfileId ?: normalized.id
        _settings.update {
            it.copy(
                profiles = current,
                baseUrl = current.firstOrNull { p -> p.id == activeId }?.baseUrl ?: normalized.baseUrl,
            )
        }
    }

    suspend fun deleteProfile(profileId: String) {
        if (profileId == ApiModelResolver.DEFAULT_PROFILE_ID) return
        val current = _settings.value.profiles.filterNot { it.id == profileId }
        if (current.isEmpty()) return
        persistProfiles(current)
        store.remove(SecureKeys.apiKey(profileId))
        store.remove(cachedModelsKey(profileId))
        val bindings = _settings.value.taskBindings.mapValues { (_, binding) ->
            if (binding.profileId == profileId) binding.copy(profileId = current.first().id)
            else binding
        }
        persistTaskBindings(bindings)
        val activeId = when (_settings.value.activeProfileId) {
            profileId -> current.first().id
            else -> _settings.value.activeProfileId ?: current.first().id
        }
        store.putString(KEY_ACTIVE_PROFILE, activeId)
        val active = current.first { it.id == activeId }
        _settings.update {
            it.copy(
                profiles = current,
                taskBindings = bindings,
                activeProfileId = activeId,
                baseUrl = active.baseUrl,
                cachedModelsByProfile = it.cachedModelsByProfile - profileId,
                cachedModels = it.cachedModelsByProfile[activeId].orEmpty(),
                hasApiKey = store.contains(SecureKeys.apiKey(activeId)) ||
                    (activeId == ApiModelResolver.DEFAULT_PROFILE_ID &&
                        store.contains(SecureKeys.AI_API_KEY)),
            )
        }
    }

    suspend fun setActiveProfile(profileId: String) {
        val profile = _settings.value.profiles.firstOrNull { it.id == profileId } ?: return
        store.putString(KEY_ACTIVE_PROFILE, profileId)
        val cached = _settings.value.cachedModelsByProfile[profileId].orEmpty()
        val hasKey = store.contains(SecureKeys.apiKey(profileId)) ||
            (profileId == ApiModelResolver.DEFAULT_PROFILE_ID &&
                store.contains(SecureKeys.AI_API_KEY))
        _settings.update {
            it.copy(
                activeProfileId = profileId,
                baseUrl = profile.baseUrl,
                cachedModels = cached,
                hasApiKey = hasKey,
            )
        }
    }

    suspend fun setTaskBinding(task: AiApiTask, binding: TaskModelBinding) {
        val updated = _settings.value.taskBindings.toMutableMap()
        updated[task.name] = binding
        persistTaskBindings(updated)
        val mirrors = mirrorBindings(updated)
        _settings.update {
            it.copy(
                taskBindings = updated,
                transcriptionModel = mirrors.transcription,
                diarizationModel = mirrors.diarization,
                chatModel = mirrors.chat,
            )
        }
        // Legacy plain keys for default-profile convenience mirrors.
        writeOptional(KEY_TRANSCRIPTION_MODEL, mirrors.transcription)
        writeOptional(KEY_DIARIZATION_MODEL, mirrors.diarization)
        writeOptional(KEY_CHAT_MODEL, mirrors.chat)
    }

    suspend fun setModels(models: List<AiModelInfo>) {
        setProfileModels(defaultOrActiveProfileId(), models)
    }

    suspend fun setProfileModels(profileId: String, models: List<AiModelInfo>) {
        store.putString(cachedModelsKey(profileId), json.encodeToString(models))
        if (profileId == ApiModelResolver.DEFAULT_PROFILE_ID ||
            profileId == _settings.value.activeProfileId
        ) {
            store.putString(KEY_CACHED_MODELS, json.encodeToString(models))
        }
        _settings.update {
            val byProfile = it.cachedModelsByProfile + (profileId to models)
            it.copy(
                cachedModelsByProfile = byProfile,
                cachedModels = if (profileId == it.activeProfileId) models else it.cachedModels,
            )
        }
    }

    suspend fun setTranscriptionModel(modelId: String?) {
        val binding = _settings.value.taskBindings[AiApiTask.Transcription.name]
            ?: TaskModelBinding(profileId = defaultOrActiveProfileId())
        setTaskBinding(AiApiTask.Transcription, binding.copy(modelId = modelId))
    }

    suspend fun setChatModel(modelId: String?) {
        val binding = _settings.value.taskBindings[AiApiTask.Summary.name]
            ?: TaskModelBinding(profileId = defaultOrActiveProfileId())
        setTaskBinding(AiApiTask.Summary, binding.copy(modelId = modelId))
        // Ask mirrors Summary chat model unless explicitly bound.
        val ask = _settings.value.taskBindings[AiApiTask.Ask.name]
        if (ask == null || ask.modelId.isNullOrBlank()) {
            setTaskBinding(
                AiApiTask.Ask,
                (ask ?: TaskModelBinding(profileId = defaultOrActiveProfileId()))
                    .copy(modelId = modelId),
            )
        }
    }

    suspend fun setDiarizationModel(modelId: String?) {
        val binding = _settings.value.taskBindings[AiApiTask.Diarization.name]
            ?: TaskModelBinding(profileId = defaultOrActiveProfileId())
        setTaskBinding(AiApiTask.Diarization, binding.copy(modelId = modelId))
    }

    suspend fun setEmbeddingModel(modelId: String?) {
        if (modelId == null) store.remove(KEY_EMBEDDING_MODEL)
        else store.putString(KEY_EMBEDDING_MODEL, modelId)
        _settings.update { it.copy(embeddingModel = modelId) }
    }

    fun cachedModelIdsByProfile(): Map<String, List<String>> =
        _settings.value.cachedModelsByProfile.mapValues { (_, models) -> models.map { it.id } }

    fun resolveApiModel(
        task: AiApiTask,
        override: ApiModelOverride? = null,
    ): ResolvedApiModel {
        val cfg = _settings.value
        return ApiModelResolver.resolve(
            task = task,
            profiles = cfg.profiles.ifEmpty {
                listOf(
                    ApiEndpointProfile(
                        id = ApiModelResolver.DEFAULT_PROFILE_ID,
                        name = ApiModelResolver.DEFAULT_PROFILE_NAME,
                        baseUrl = cfg.baseUrl,
                    ),
                )
            },
            taskDefaults = cfg.taskBindings,
            cachedModelIdsByProfile = cachedModelIdsByProfile(),
            override = override,
        )
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

    private suspend fun loadOrMigrateProfiles(): List<ApiEndpointProfile> {
        val raw = store.getString(KEY_ENDPOINT_PROFILES)
        if (!raw.isNullOrBlank()) {
            val decoded = runCatching {
                json.decodeFromString<List<ApiEndpointProfile>>(raw)
            }.getOrDefault(emptyList())
            if (decoded.isNotEmpty()) return decoded
        }
        val baseUrl = store.getString(KEY_BASE_URL) ?: DEFAULT_BASE_URL
        val defaultProfile = ApiEndpointProfile(
            id = ApiModelResolver.DEFAULT_PROFILE_ID,
            name = ApiModelResolver.DEFAULT_PROFILE_NAME,
            baseUrl = baseUrl,
        )
        persistProfiles(listOf(defaultProfile))
        store.putString(KEY_ACTIVE_PROFILE, defaultProfile.id)
        // Migrate legacy key → per-profile key.
        val legacyKey = store.getString(SecureKeys.AI_API_KEY)
        if (!legacyKey.isNullOrBlank() && !store.contains(SecureKeys.apiKey(defaultProfile.id))) {
            store.putString(SecureKeys.apiKey(defaultProfile.id), legacyKey)
        }
        return listOf(defaultProfile)
    }

    private suspend fun loadTaskBindings(): Map<String, TaskModelBinding> {
        val raw = store.getString(KEY_TASK_BINDINGS)
        if (!raw.isNullOrBlank()) {
            val decoded = runCatching {
                json.decodeFromString<Map<String, TaskModelBinding>>(raw)
            }.getOrDefault(emptyMap())
            if (decoded.isNotEmpty()) return decoded
        }
        // Seed from legacy per-task model keys onto the default profile.
        val seeded = buildMap {
            store.getString(KEY_TRANSCRIPTION_MODEL)?.let {
                put(AiApiTask.Transcription.name, TaskModelBinding(ApiModelResolver.DEFAULT_PROFILE_ID, it))
            }
            store.getString(KEY_DIARIZATION_MODEL)?.let {
                put(AiApiTask.Diarization.name, TaskModelBinding(ApiModelResolver.DEFAULT_PROFILE_ID, it))
            }
            store.getString(KEY_CHAT_MODEL)?.let { chat ->
                put(AiApiTask.Summary.name, TaskModelBinding(ApiModelResolver.DEFAULT_PROFILE_ID, chat))
                put(AiApiTask.Ask.name, TaskModelBinding(ApiModelResolver.DEFAULT_PROFILE_ID, chat))
                put(AiApiTask.AutoTitle.name, TaskModelBinding(ApiModelResolver.DEFAULT_PROFILE_ID, chat))
                put(AiApiTask.Translation.name, TaskModelBinding(ApiModelResolver.DEFAULT_PROFILE_ID, chat))
                put(AiApiTask.AutoFlow.name, TaskModelBinding(ApiModelResolver.DEFAULT_PROFILE_ID, chat))
            }
        }
        if (seeded.isNotEmpty()) persistTaskBindings(seeded)
        return seeded
    }

    private suspend fun loadCachedModelsByProfile(
        profiles: List<ApiEndpointProfile>,
    ): Map<String, List<AiModelInfo>> {
        val result = mutableMapOf<String, List<AiModelInfo>>()
        for (profile in profiles) {
            val raw = store.getString(cachedModelsKey(profile.id))
            val models = raw?.let {
                runCatching { json.decodeFromString<List<AiModelInfo>>(it) }.getOrDefault(emptyList())
            }.orEmpty()
            if (models.isNotEmpty()) result[profile.id] = models
        }
        if (result.isEmpty()) {
            val legacy = store.getString(KEY_CACHED_MODELS)?.let {
                runCatching { json.decodeFromString<List<AiModelInfo>>(it) }.getOrDefault(emptyList())
            }.orEmpty()
            if (legacy.isNotEmpty()) {
                val id = profiles.firstOrNull()?.id ?: ApiModelResolver.DEFAULT_PROFILE_ID
                result[id] = legacy
                store.putString(cachedModelsKey(id), json.encodeToString(legacy))
            }
        }
        return result
    }

    private suspend fun persistProfiles(profiles: List<ApiEndpointProfile>) {
        store.putString(KEY_ENDPOINT_PROFILES, json.encodeToString(profiles))
    }

    private suspend fun persistTaskBindings(bindings: Map<String, TaskModelBinding>) {
        store.putString(KEY_TASK_BINDINGS, json.encodeToString(bindings))
    }

    private fun bindingModel(
        bindings: Map<String, TaskModelBinding>,
        task: AiApiTask,
    ): String? = bindings[task.name]?.modelId?.takeIf { it.isNotBlank() }

    private data class MirrorModels(
        val transcription: String?,
        val diarization: String?,
        val chat: String?,
    )

    private fun mirrorBindings(bindings: Map<String, TaskModelBinding>): MirrorModels =
        MirrorModels(
            transcription = bindingModel(bindings, AiApiTask.Transcription),
            diarization = bindingModel(bindings, AiApiTask.Diarization),
            chat = bindingModel(bindings, AiApiTask.Summary),
        )

    private fun defaultOrActiveProfileId(): String =
        _settings.value.activeProfileId
            ?: _settings.value.profiles.firstOrNull()?.id
            ?: ApiModelResolver.DEFAULT_PROFILE_ID

    private fun profileName(profileId: String): String =
        _settings.value.profiles.firstOrNull { it.id == profileId }?.name
            ?: if (profileId == ApiModelResolver.DEFAULT_PROFILE_ID) {
                ApiModelResolver.DEFAULT_PROFILE_NAME
            } else {
                profileId
            }

    private suspend fun refreshHasApiKeyFlags() {
        val activeId = defaultOrActiveProfileId()
        val hasKey = store.contains(SecureKeys.apiKey(activeId)) ||
            (activeId == ApiModelResolver.DEFAULT_PROFILE_ID &&
                store.contains(SecureKeys.AI_API_KEY))
        _settings.update { it.copy(hasApiKey = hasKey) }
    }

    private fun cachedModelsKey(profileId: String): String = "$KEY_CACHED_MODELS_PREFIX$profileId"

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
        private const val KEY_DIARIZATION_MODEL = "ai.diarization_model"
        private const val KEY_CHAT_MODEL = "ai.chat_model"
        private const val KEY_EMBEDDING_MODEL = "ai.embedding_model"
        private const val KEY_CACHED_MODELS = "ai.cached_models_json"
        private const val KEY_CACHED_MODELS_PREFIX = "ai.cached_models."
        private const val KEY_ENDPOINT_PROFILES = "ai.endpoint_profiles_json"
        private const val KEY_TASK_BINDINGS = "ai.task_model_bindings_json"
        private const val KEY_ACTIVE_PROFILE = "ai.active_profile_id"
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
