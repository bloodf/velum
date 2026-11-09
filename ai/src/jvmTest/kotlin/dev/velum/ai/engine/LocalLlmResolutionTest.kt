package dev.velum.ai.engine

import dev.velum.ai.local.LocalLlmEngine
import dev.velum.ai.local.LocalModelKind
import dev.velum.ai.local.LocalModelManager
import dev.velum.ai.local.LocalModelSpec
import dev.velum.ai.local.LOCAL_MODEL_NOT_READY_MESSAGE
import dev.velum.ai.local.ModelNotReadyException
import dev.velum.ai.local.ModelStoreDir
import dev.velum.core.model.AiProviderSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

/** #260: every route to the local LLM agrees on one model, and it is one the user actually has. */
class LocalLlmResolutionTest {
    private val higherQuality = "gemma-4-e4b"
    private val other = "qwen3-4b-instruct-2507"

    private fun manager(vararg ready: String): LocalModelManager {
        val dir = File.createTempFile("velum-llm-260", "").also { it.delete(); it.mkdirs() }
        ready.forEach { id -> File(dir, "$id/weights.litertlm").apply { parentFile.mkdirs(); writeBytes(ByteArray(2048)) } }
        fun spec(id: String) = LocalModelSpec(
            id = id, displayName = "Synthetic $id", kind = LocalModelKind.Llm, approxBytes = 1, license = "test",
            notes = "", languages = "en", downloadUrl = "https://example.test/$id", sha256 = dev.velum.ai.local.sha256Hex(ByteArray(2048)),
            archiveFileName = "weights.litertlm",
        )
        return LocalModelManager(
            ModelStoreDir(dir.absolutePath),
            HttpClient(MockEngine { respond("") }),
            listOf(spec(higherQuality), spec(DEFAULT_LOCAL_LLM_MODEL_ID), spec(other)),
        )
    }

    private fun summaryResolution(manager: LocalModelManager, selected: String? = null) =
        resolveModeOrPrompt(AiTask.Summary, AiProviderSettings(localLlmModelId = selected), manager)

    @Test fun the_offered_default_is_the_lighter_model_the_recommendation_downloads() {
        assertEquals("gemma-4-e2b", DEFAULT_LOCAL_LLM_MODEL_ID)
    }

    @Test fun nothing_selected_uses_the_lighter_model_when_it_is_the_only_one_ready() {
        val manager = manager(DEFAULT_LOCAL_LLM_MODEL_ID)
        assertEquals(DEFAULT_LOCAL_LLM_MODEL_ID, resolveLocalLlmModelId(null, manager))
        assertIs<ModeResolution.Proceed>(summaryResolution(manager))
    }

    @Test fun nothing_selected_uses_the_higher_quality_model_when_only_it_is_ready() {
        val manager = manager(higherQuality)
        assertEquals(higherQuality, resolveLocalLlmModelId("", manager))
        assertIs<ModeResolution.Proceed>(summaryResolution(manager))
    }

    @Test fun nothing_selected_prefers_higher_quality_when_both_are_ready() {
        assertEquals(higherQuality, resolveLocalLlmModelId(null, manager(higherQuality, DEFAULT_LOCAL_LLM_MODEL_ID)))
    }

    @Test fun nothing_ready_offers_the_default() {
        val prompt = assertIs<ModeResolution.NeedLocalModel>(summaryResolution(manager()))
        assertEquals(DEFAULT_LOCAL_LLM_MODEL_ID, prompt.modelId)
    }

    @Test fun models_the_runtime_cannot_load_are_never_picked_automatically() {
        val manager = manager(other)
        assertEquals(DEFAULT_LOCAL_LLM_MODEL_ID, resolveLocalLlmModelId(null, manager))
        assertIs<ModeResolution.NeedLocalModel>(summaryResolution(manager))
    }

    @Test fun a_selected_model_that_is_missing_prompts_for_it_even_if_another_is_ready() {
        val manager = manager(DEFAULT_LOCAL_LLM_MODEL_ID)
        assertEquals(higherQuality, resolveLocalLlmModelId(higherQuality, manager))
        val prompt = assertIs<ModeResolution.NeedLocalModel>(summaryResolution(manager, selected = higherQuality))
        assertEquals(higherQuality, prompt.modelId)
    }

    @Test fun the_engine_routes_a_blank_id_through_the_same_resolution() = runBlocking {
        val request = LlmRequest(systemPrompt = "s", userPrompt = "u")
        val missing = assertFailsWith<ModelNotReadyException> { LocalLlmEngine(manager()).complete(request, "") }
        assertEquals(DEFAULT_LOCAL_LLM_MODEL_ID, missing.modelId)
        // Past the readiness check the JVM runtime refuses for its own reason, not a missing model.
        val ready = runCatching { LocalLlmEngine(manager(DEFAULT_LOCAL_LLM_MODEL_ID)).complete(request, "") }
        assertFalse(ready.exceptionOrNull() is ModelNotReadyException)
    }

    @Test fun the_not_ready_message_names_no_model_or_status() {
        val text = ModelNotReadyException("gemma-4-e4b").message.orEmpty()
        assertEquals(LOCAL_MODEL_NOT_READY_MESSAGE, text)
        listOf("gemma", "status", "Ready", "NotDownloaded", "LLM").forEach { assertFalse(it in text, it) }
    }
}
