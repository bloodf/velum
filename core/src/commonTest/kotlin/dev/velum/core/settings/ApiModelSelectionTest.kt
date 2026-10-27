package dev.velum.core.settings

import dev.velum.core.model.AiModelInfo
import dev.velum.core.security.InMemorySecureStore
import dev.velum.core.security.SecureKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        assertFalse(repo.settings.value.toString().contains("sk-secret"))

        val reloaded = SettingsRepository(store)
        reloaded.load()
        assertTrue(reloaded.settings.value.hasApiKey)
        assertEquals("whisper-1", reloaded.settings.value.transcriptionModel)
        assertEquals("diarize-x", reloaded.settings.value.diarizationModel)
        assertEquals("gpt-4o-mini", reloaded.settings.value.chatModel)
        assertEquals("text-embedding-3-small", reloaded.settings.value.embeddingModel)
    }
}
