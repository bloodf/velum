package dev.velum.core.settings

import kotlinx.serialization.Serializable

/** OpenAI-compatible API tasks that can bind a distinct endpoint profile + model. */
enum class AiApiTask {
    Transcription,
    Diarization,
    Summary,
    Ask,
    AutoTitle,
    Translation,
    AutoFlow,
}

@Serializable
data class ApiEndpointProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
)

@Serializable
data class TaskModelBinding(
    val profileId: String? = null,
    val modelId: String? = null,
)

@Serializable
data class ApiModelOverride(
    val profileId: String? = null,
    val modelId: String? = null,
)

data class ResolvedApiModel(
    val profileId: String,
    val profileName: String,
    val baseUrl: String,
    val modelId: String,
    val usedFallback: Boolean,
    val fallbackMessage: String? = null,
)

/**
 * Pure resolution: override > task default > first profile + hard fallback model.
 * Never includes API keys in messages.
 */
object ApiModelResolver {
    const val DEFAULT_PROFILE_ID: String = "default"
    const val DEFAULT_PROFILE_NAME: String = "Default"

    fun defaultHardFallbacks(): Map<AiApiTask, String> = buildMap {
        put(AiApiTask.Transcription, "whisper-1")
        put(AiApiTask.Diarization, "")
        put(AiApiTask.Summary, "gpt-4o-mini")
        put(AiApiTask.Ask, "gpt-4o-mini")
        put(AiApiTask.AutoTitle, "gpt-4o-mini")
        put(AiApiTask.Translation, "gpt-4o-mini")
        put(AiApiTask.AutoFlow, "gpt-4o-mini")
    }

    fun hardFallbackModel(task: AiApiTask): String =
        defaultHardFallbacks()[task].orEmpty()

    fun resolve(
        task: AiApiTask,
        profiles: List<ApiEndpointProfile>,
        taskDefaults: Map<String, TaskModelBinding>,
        cachedModelIdsByProfile: Map<String, List<String>>,
        override: ApiModelOverride? = null,
        hardFallbacks: Map<AiApiTask, String> = defaultHardFallbacks(),
    ): ResolvedApiModel {
        val hardModel = hardFallbacks[task] ?: hardFallbackModel(task)
        val defaultBinding = taskDefaults[task.name] ?: TaskModelBinding()
        val profileIdRaw = override?.profileId?.takeIf { it.isNotBlank() }
            ?: defaultBinding.profileId?.takeIf { it.isNotBlank() }
        val modelIdRaw = override?.modelId?.takeIf { it.isNotBlank() }
            ?: defaultBinding.modelId?.takeIf { it.isNotBlank() }

        val firstProfile = profiles.firstOrNull()
            ?: ApiEndpointProfile(
                id = DEFAULT_PROFILE_ID,
                name = DEFAULT_PROFILE_NAME,
                baseUrl = SettingsRepository.DEFAULT_BASE_URL,
            )

        var usedFallback = false
        var fallbackMessage: String? = null

        val profile = when {
            profileIdRaw == null -> firstProfile
            else -> {
                val found = profiles.firstOrNull { it.id == profileIdRaw }
                if (found == null) {
                    usedFallback = true
                    fallbackMessage =
                        "Endpoint profile \"$profileIdRaw\" is missing; using \"${firstProfile.name}\"."
                    firstProfile
                } else {
                    found
                }
            }
        }

        var modelId = modelIdRaw ?: hardModel
        if (modelId.isEmpty() && hardModel.isNotEmpty()) {
            modelId = hardModel
        }

        val cached = cachedModelIdsByProfile[profile.id].orEmpty()
        if (cached.isNotEmpty() && modelId.isNotEmpty() && modelId !in cached) {
            usedFallback = true
            val replacement = when {
                hardModel.isNotEmpty() && hardModel in cached -> hardModel
                else -> cached.first()
            }
            fallbackMessage =
                "Model \"$modelId\" is no longer listed for \"${profile.name}\"; using \"$replacement\"."
            modelId = replacement
        } else if (cached.isNotEmpty() && modelId.isEmpty()) {
            usedFallback = true
            modelId = when {
                hardModel.isNotEmpty() && hardModel in cached -> hardModel
                else -> cached.first()
            }
            fallbackMessage =
                "No model selected for ${task.name}; using \"$modelId\" on \"${profile.name}\"."
        }

        return ResolvedApiModel(
            profileId = profile.id,
            profileName = profile.name,
            baseUrl = profile.baseUrl,
            modelId = modelId,
            usedFallback = usedFallback,
            fallbackMessage = fallbackMessage,
        )
    }
}
