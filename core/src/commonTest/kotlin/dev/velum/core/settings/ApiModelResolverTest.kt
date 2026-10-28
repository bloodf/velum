package dev.velum.core.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ApiModelResolverTest {
    private val profiles = listOf(
        ApiEndpointProfile("default", "Default", "https://api.openai.com/v1"),
        ApiEndpointProfile("ollama", "Ollama", "http://127.0.0.1:11434/v1"),
    )

    @Test
    fun override_beats_task_default_beats_hard_fallback() {
        val defaults = mapOf(
            AiApiTask.Summary.name to TaskModelBinding("default", "gpt-4o-mini"),
        )
        val withOverride = ApiModelResolver.resolve(
            task = AiApiTask.Summary,
            profiles = profiles,
            taskDefaults = defaults,
            cachedModelIdsByProfile = emptyMap(),
            override = ApiModelOverride(profileId = "ollama", modelId = "llama3.2"),
        )
        assertEquals("ollama", withOverride.profileId)
        assertEquals("llama3.2", withOverride.modelId)
        assertFalse(withOverride.usedFallback)

        val fromDefault = ApiModelResolver.resolve(
            task = AiApiTask.Summary,
            profiles = profiles,
            taskDefaults = defaults,
            cachedModelIdsByProfile = emptyMap(),
            override = null,
        )
        assertEquals("default", fromDefault.profileId)
        assertEquals("gpt-4o-mini", fromDefault.modelId)

        val hardFallback = ApiModelResolver.resolve(
            task = AiApiTask.Ask,
            profiles = profiles,
            taskDefaults = emptyMap(),
            cachedModelIdsByProfile = emptyMap(),
            override = null,
        )
        assertEquals("default", hardFallback.profileId)
        assertEquals("gpt-4o-mini", hardFallback.modelId)
    }

    @Test
    fun missing_model_in_refreshed_list_falls_back_with_clear_message_without_key() {
        val resolved = ApiModelResolver.resolve(
            task = AiApiTask.Summary,
            profiles = profiles,
            taskDefaults = mapOf(
                AiApiTask.Summary.name to TaskModelBinding("default", "retired-model"),
            ),
            cachedModelIdsByProfile = mapOf(
                "default" to listOf("gpt-4o-mini", "gpt-4o"),
            ),
            override = null,
        )
        assertTrue(resolved.usedFallback)
        assertEquals("gpt-4o-mini", resolved.modelId)
        val message = assertNotNull(resolved.fallbackMessage)
        assertTrue(message.contains("retired-model"))
        assertTrue(message.contains("gpt-4o-mini"))
        assertFalse(message.contains("sk-", ignoreCase = true))
        assertFalse(message.contains("api_key", ignoreCase = true))
        assertFalse(message.contains("apiKey", ignoreCase = true))
    }

    @Test
    fun missing_profile_falls_back_to_first_profile() {
        val resolved = ApiModelResolver.resolve(
            task = AiApiTask.Transcription,
            profiles = profiles,
            taskDefaults = mapOf(
                AiApiTask.Transcription.name to TaskModelBinding("gone", "whisper-1"),
            ),
            cachedModelIdsByProfile = emptyMap(),
            override = null,
        )
        assertEquals("default", resolved.profileId)
        assertEquals("whisper-1", resolved.modelId)
        assertTrue(resolved.usedFallback)
        assertNotNull(resolved.fallbackMessage)
        assertTrue(resolved.fallbackMessage!!.contains("gone"))
    }

    @Test
    fun override_partial_fields_merge_with_task_default() {
        val resolved = ApiModelResolver.resolve(
            task = AiApiTask.AutoFlow,
            profiles = profiles,
            taskDefaults = mapOf(
                AiApiTask.AutoFlow.name to TaskModelBinding("default", "gpt-4o-mini"),
            ),
            cachedModelIdsByProfile = emptyMap(),
            override = ApiModelOverride(profileId = "ollama"),
        )
        assertEquals("ollama", resolved.profileId)
        assertEquals("gpt-4o-mini", resolved.modelId)
    }

    @Test
    fun mergeApiOverrides_fills_blank_model_from_template() {
        // Mirrors RecordingProcessor.mergeApiOverrides (AutoFlow profile-only override).
        fun merge(
            primary: ApiModelOverride?,
            secondary: ApiModelOverride?,
        ): ApiModelOverride? {
            if (primary == null) return secondary
            if (secondary == null) return primary
            return ApiModelOverride(
                profileId = primary.profileId?.takeIf { it.isNotBlank() }
                    ?: secondary.profileId?.takeIf { it.isNotBlank() },
                modelId = primary.modelId?.takeIf { it.isNotBlank() }
                    ?: secondary.modelId?.takeIf { it.isNotBlank() },
            )
        }
        val merged = merge(
            ApiModelOverride(profileId = "ollama", modelId = null),
            ApiModelOverride(profileId = null, modelId = "llama3"),
        )
        assertEquals("ollama", merged!!.profileId)
        assertEquals("llama3", merged.modelId)
    }

    @Test
    fun diarization_hard_fallback_may_be_empty() {
        val resolved = ApiModelResolver.resolve(
            task = AiApiTask.Diarization,
            profiles = profiles,
            taskDefaults = emptyMap(),
            cachedModelIdsByProfile = emptyMap(),
        )
        assertEquals("", resolved.modelId)
        assertEquals("default", resolved.profileId)
    }
}
