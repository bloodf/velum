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
import kotlinx.coroutines.async
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
    fun local_without_ready_model_prompts() = kotlinx.coroutines.test.runTest {
        val settings = AiProviderSettings(transcriptionMode = ComputeMode.Local)
        val manager = emptyManager()
        manager.awaitInitialization()
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

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun action_waits_for_verification_before_selecting_installed_model() = kotlinx.coroutines.test.runTest {
        val io = kotlinx.coroutines.test.TestCoroutineScheduler()
        val root = ModelStoreDir(java.nio.file.Files.createTempDirectory("velum-route-pending").toString())
        val bytes = "verified weights".encodeToByteArray()
        val spec = LocalModelSpec(
            "gemma-4-e4b", "Gemma", LocalModelKind.Llm, bytes.size.toLong(), "test", "", "en",
            "https://example.test/model.litertlm", dev.velum.ai.local.sha256Hex(bytes), "model.litertlm",
        )
        root.resolve(spec.id).resolve(spec.archiveFileName).writeBytes(bytes)
        val client = HttpClient(MockEngine { error("must stay offline") })
        try {
            val manager = LocalModelManager(root, client, listOf(spec), kotlinx.coroutines.test.StandardTestDispatcher(io))
            assertEquals(ModeResolution.Initializing, resolveModeOrPrompt(AiTask.Summary, AiProviderSettings(), manager))
            var selected: String? = null
            val action = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                val result = resolveModeAfterInitialization(AiTask.Summary, { AiProviderSettings() }, manager)
                selected = resolveLocalLlmModelId(null, manager)
                result
            }
            assertTrue(!action.isCompleted)
            assertEquals(null, selected)
            io.runCurrent()
            assertEquals(ModeResolution.Proceed(ComputeMode.Local), action.await())
            assertEquals(spec.id, selected)
        } finally {
            client.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun switching_from_api_to_local_without_a_model_prompts_for_download() = kotlinx.coroutines.test.runTest {
        val manager = emptyManager()
        for (task in listOf(AiTask.Transcription, AiTask.Summary)) {
            val saved = AiProviderSettings(transcriptionMode = ComputeMode.Api, summaryMode = ComputeMode.Api)
            // The saved mode alone says "proceed with API", which is how Local used to be saved without a model.
            assertEquals(ModeResolution.Proceed(ComputeMode.Api), resolveModeOrPrompt(task, saved, manager))
            assertIs<ModeResolution.NeedLocalModel>(resolveLocalSelection(task, { saved }, manager))
        }
    }

    @Test
    fun switching_to_local_with_the_model_installed_proceeds_locally() = kotlinx.coroutines.test.runTest {
        val root = ModelStoreDir(java.nio.file.Files.createTempDirectory("velum-local-switch").toString())
        val bytes = "verified weights".encodeToByteArray()
        val spec = LocalModelSpec(
            "gemma-4-e4b", "Gemma", LocalModelKind.Llm, bytes.size.toLong(), "test", "", "en",
            "https://example.test/model.litertlm", dev.velum.ai.local.sha256Hex(bytes), "model.litertlm",
        )
        root.resolve(spec.id).resolve(spec.archiveFileName).writeBytes(bytes)
        val client = HttpClient(MockEngine { error("must stay offline") })
        try {
            val manager = LocalModelManager(root, client, listOf(spec))
            val saved = AiProviderSettings(summaryMode = ComputeMode.Api)
            assertEquals(ModeResolution.Proceed(ComputeMode.Local), resolveLocalSelection(AiTask.Summary, { saved }, manager))
        } finally {
            client.close()
            root.deleteRecursively()
        }
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
