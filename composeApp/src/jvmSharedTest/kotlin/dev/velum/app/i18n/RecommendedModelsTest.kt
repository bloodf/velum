package dev.velum.app.i18n

import dev.velum.ai.engine.AiTask
import dev.velum.ai.engine.ModeResolution
import dev.velum.ai.engine.resolveLocalLlmModelId
import dev.velum.ai.engine.resolveModeOrPrompt
import dev.velum.ai.local.DefaultLocalCatalog
import dev.velum.ai.local.DeviceComputeClass
import dev.velum.ai.local.LOCAL_MODEL_NOT_READY_MESSAGE
import dev.velum.ai.local.LocalModelKind
import dev.velum.ai.local.ModelNotReadyException
import dev.velum.ai.local.ModelStoreDir
import dev.velum.ai.local.createLocalModelManager
import dev.velum.core.model.AiProviderSettings
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** #260: following the app's own recommendation must be enough for a first local summary. */
class RecommendedModelsTest {
    @Test fun the_recommended_set_is_enough_for_a_local_summary_on_every_device_class() = runBlocking {
        DeviceComputeClass.entries.forEach { deviceClass ->
            val llms = ModelCatalogUi.recommendedModelIds("pt-BR", deviceClass).filter { id ->
                DefaultLocalCatalog.first { it.id == id }.kind == LocalModelKind.Llm
            }
            val dir = File.createTempFile("velum-recommended", "").also { it.delete(); it.mkdirs() }
            // Synthetic weights need their own checksum; production catalog pins remain unchanged.
            val catalog = DefaultLocalCatalog.map { spec ->
                if (spec.id in llms) spec.copy(
                    sha256 = "e5a00aa9991ac8a5ee3109844d84a55583bd20572ad3ffcd42792f3c36b183ad",
                ) else spec
            }
            try {
                llms.forEach { id ->
                    val spec = catalog.first { it.id == id }
                    File(dir, "$id/${spec.archiveFileName}").apply {
                        parentFile?.mkdirs()
                        writeBytes(ByteArray(2048))
                    }
                }
                val manager = createLocalModelManager(ModelStoreDir(dir.absolutePath), catalog = catalog)
                manager.awaitInitialization()
                llms.forEach { assertTrue(manager.isReady(it), "$deviceClass fixture not verified: $it") }
                assertTrue(resolveLocalLlmModelId(null, manager) in llms, "$deviceClass resolved outside $llms")
                assertIs<ModeResolution.Proceed>(resolveModeOrPrompt(AiTask.Summary, AiProviderSettings(), manager))
            } finally {
                dir.deleteRecursively()
            }
        }
    }

    @Test fun a_missing_model_failure_reads_as_plain_copy_in_both_languages() {
        listOf(En, PtBr).forEach { strings ->
            val copy = processingFailureText(ModelNotReadyException("gemma-4-e4b").message, strings)
            assertEquals(strings.ux.localModelNotReady, copy)
            listOf("gemma", "status", "Ready", "LLM").forEach { assertFalse(it in copy.orEmpty(), it) }
        }
        assertTrue(PtBr.ux.localModelNotReady != En.ux.localModelNotReady)
        assertEquals(LOCAL_MODEL_NOT_READY_MESSAGE, ModelNotReadyException("x").message)
    }

    @Test fun text_already_saved_by_older_versions_is_translated_too() {
        val saved = "Local LLM 'gemma-4-e4b' is not Ready (status=NotDownloaded). Download it in Model manager, or switch Summaries to API."
        assertEquals(PtBr.ux.localModelNotReady, processingFailureText(saved, PtBr))
        assertEquals(PtBr.ux.localModelNotReady, processingFailureText("Local model 'x' is not Ready. Download it in Model manager first.", PtBr))
    }

    @Test fun other_failures_use_the_screen_generic_copy() {
        assertEquals(null, processingFailureText("HTTP 500", En))
        assertEquals(null, processingFailureText("HTTP 500.", PtBr))
        assertEquals(null, processingFailureText(null, En))
    }
}
