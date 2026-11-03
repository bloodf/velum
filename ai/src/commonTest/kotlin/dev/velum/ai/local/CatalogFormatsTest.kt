package dev.velum.ai.local

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CatalogFormatsTest {
    @Test fun every_catalog_entry_is_a_format_a_runtime_can_load() {
        val unloadable = DefaultLocalCatalog.filterNot { LocalModelFormats.isLoadable(it) }.map { "${it.id} (${it.archiveFileName})" }
        assertEquals(emptyList(), unloadable, "catalog offers models no runtime can load")
    }

    @Test fun llm_weights_are_litertlm_or_task_only() {
        assertTrue(LocalModelFormats.isLoadableLlmWeights("gemma-4-E4B-it.litertlm"))
        assertTrue(LocalModelFormats.isLoadableLlmWeights("model.TASK"))
        assertFalse(LocalModelFormats.isLoadableLlmWeights("Qwen3-4B-Instruct-2507-Q4_K_M.gguf"))
        assertFalse(LocalModelFormats.isLoadableLlmWeights("model.tflite"))
    }

    @Test fun a_gguf_llm_entry_would_be_rejected() {
        val gguf = LocalModelSpec("x", "X", LocalModelKind.Llm, 1, "MIT", "", "", archiveFileName = "x-Q4_K_M.gguf")
        assertFalse(LocalModelFormats.isLoadable(gguf))
    }

    @Test fun catalog_still_has_the_gemma_llms() {
        val llms = DefaultLocalCatalog.filter { it.kind == LocalModelKind.Llm }.map { it.id }
        assertEquals(listOf("gemma-4-e4b", "gemma-4-e2b"), llms)
    }
}
