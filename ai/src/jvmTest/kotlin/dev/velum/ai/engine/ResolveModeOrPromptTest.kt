package dev.velum.ai.engine

import dev.velum.ai.local.LocalModelKind
import dev.velum.ai.local.LocalModelManager
import dev.velum.ai.local.LocalModelSpec
import dev.velum.ai.local.LocalModelStatus
import dev.velum.ai.local.ModelStoreDir
import dev.velum.core.model.AiProviderSettings
import dev.velum.core.model.ComputeMode
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ResolveModeOrPromptTest {
    @Test
    fun api_mode_proceeds_without_model() {
        val settings = AiProviderSettings(transcriptionMode = ComputeMode.Api)
        val manager = emptyManager()
        val result = resolveModeOrPrompt(AiTask.Transcription, settings, manager)
        assertEquals(ModeResolution.Proceed(ComputeMode.Api), result)
    }

    @Test
    fun local_without_ready_model_prompts() {
        val settings = AiProviderSettings(transcriptionMode = ComputeMode.Local)
        val manager = emptyManager()
        val result = resolveModeOrPrompt(AiTask.Transcription, settings, manager)
        assertIs<ModeResolution.NeedLocalModel>(result)
        assertEquals(DEFAULT_LOCAL_ASR_MODEL_ID, result.modelId)
    }

    @Test
    fun defaults_are_local() {
        val settings = AiProviderSettings()
        assertEquals(ComputeMode.Local, settings.transcriptionMode)
        assertEquals(ComputeMode.Local, settings.diarizationMode)
        assertEquals(ComputeMode.Local, settings.summaryMode)
    }

    private fun emptyManager(): LocalModelManager {
        val root = ModelStoreDir(
            File.createTempFile("velum-resolve-mode", "").also {
                it.delete()
                it.mkdirs()
            }.absolutePath,
        )
        return LocalModelManager(
            storeRoot = root,
            httpClient = HttpClient(MockEngine { respond("") }),
            catalog = listOf(
                LocalModelSpec(
                    id = DEFAULT_LOCAL_ASR_MODEL_ID,
                    displayName = "Parakeet",
                    kind = LocalModelKind.Asr,
                    approxBytes = 1,
                    license = "test",
                    notes = "",
                    languages = "en",
                    downloadUrl = null,
                ),
            ),
        ).also {
            assertTrue(it.stateFor(DEFAULT_LOCAL_ASR_MODEL_ID)?.status != LocalModelStatus.Ready)
        }
    }
}
