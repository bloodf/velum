package dev.velum.core.settings

import dev.velum.core.model.AiModelInfo
import dev.velum.core.security.InMemorySecureStore
import dev.velum.core.security.SecureKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ApiModelSelectionTest {
    @Test
    fun perTaskModelsRoundTripAndKeyNeverInPlainSettings() = runTest {
        val store = InMemorySecureStore()
        val repo = SettingsRepository(store)
        repo.load()

        repo.setApiKey("sk-secret-never-log")
        repo.setTranscriptionModel("whisper-1")
        repo.setDiarizationModel("diarize-x")
        repo.setChatModel("gpt-4o-mini")
        repo.setEmbeddingModel("text-embedding-3-small")
        repo.setModels(
            listOf(
                AiModelInfo("gpt-4o-mini", "openai"),
                AiModelInfo("whisper-1", "openai"),
                AiModelInfo("text-embedding-3-small", "openai"),
            ),
        )

        assertTrue(repo.settings.value.hasApiKey)
        assertEquals("whisper-1", repo.settings.value.transcriptionModel)
        assertEquals("diarize-x", repo.settings.value.diarizationModel)
        assertEquals("gpt-4o-mini", repo.settings.value.chatModel)
        assertEquals("text-embedding-3-small", repo.settings.value.embeddingModel)
        assertEquals(3, repo.settings.value.cachedModels.size)

        // Key is only in secure store under SecureKeys — not mirrored into settings JSON fields.
        assertEquals("sk-secret-never-log", store.getString(SecureKeys.AI_API_KEY))
        assertEquals(
            "sk-secret-never-log",
            store.getString(SecureKeys.apiKey(ApiModelResolver.DEFAULT_PROFILE_ID)),
        )
        assertFalse(repo.settings.value.toString().contains("sk-secret"))

        val reloaded = SettingsRepository(store)
        reloaded.load()
        assertTrue(reloaded.settings.value.hasApiKey)
        assertEquals("whisper-1", reloaded.settings.value.transcriptionModel)
        assertEquals("diarize-x", reloaded.settings.value.diarizationModel)
        assertEquals("gpt-4o-mini", reloaded.settings.value.chatModel)
        assertEquals("text-embedding-3-small", reloaded.settings.value.embeddingModel)
        assertEquals(1, reloaded.settings.value.profiles.size)
        assertEquals(ApiModelResolver.DEFAULT_PROFILE_ID, reloaded.settings.value.profiles.first().id)
    }

    @Test
    fun multiProfileKeysUnderPrefixedSecureKeys_andLegacyMigration() = runTest {
        val store = InMemorySecureStore()
        // Legacy single-endpoint install.
        store.putString("ai.base_url", "https://api.openai.com/v1")
        store.putString(SecureKeys.AI_API_KEY, "sk-legacy-key")
        store.putString("ai.chat_model", "gpt-4o-mini")

        val repo = SettingsRepository(store)
        repo.load()

        assertEquals(1, repo.settings.value.profiles.size)
        assertEquals(ApiModelResolver.DEFAULT_PROFILE_ID, repo.settings.value.activeProfileId)
        assertEquals("sk-legacy-key", repo.getApiKey())
        assertEquals(
            "sk-legacy-key",
            repo.getProfileApiKey(ApiModelResolver.DEFAULT_PROFILE_ID),
        )
        assertEquals(
            "sk-legacy-key",
            store.getString(SecureKeys.apiKey(ApiModelResolver.DEFAULT_PROFILE_ID)),
        )
        assertFalse(repo.settings.value.toString().contains("sk-legacy"))

        repo.upsertProfile(
            ApiEndpointProfile(
                id = "ollama",
                name = "Ollama",
                baseUrl = "http://127.0.0.1:11434/v1",
            ),
        )
        repo.setProfileApiKey("ollama", "ollama-local-token")
        repo.setProfileModels(
            "ollama",
            listOf(AiModelInfo("llama3.2"), AiModelInfo("whisper")),
        )
        repo.setTaskBinding(
            AiApiTask.Summary,
            TaskModelBinding(profileId = "ollama", modelId = "llama3.2"),
        )

        assertEquals("ollama-local-token", store.getString(SecureKeys.apiKey("ollama")))
        assertEquals("sk-legacy-key", store.getString(SecureKeys.AI_API_KEY))
        assertNull(store.getString(SecureKeys.apiKey("missing")))
        assertFalse(repo.settings.value.toString().contains("ollama-local"))
        assertFalse(repo.settings.value.toString().contains("sk-legacy"))

        val resolved = repo.resolveApiModel(AiApiTask.Summary)
        assertEquals("ollama", resolved.profileId)
        assertEquals("llama3.2", resolved.modelId)

        val reloaded = SettingsRepository(store)
        reloaded.load()
        assertEquals(2, reloaded.settings.value.profiles.size)
        assertEquals(
            "llama3.2",
            reloaded.settings.value.taskBindings[AiApiTask.Summary.name]?.modelId,
        )
        assertEquals("ollama-local-token", reloaded.getProfileApiKey("ollama"))
    }
}
