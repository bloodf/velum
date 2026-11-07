package dev.velum.ai.local

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RetiredModelsTest {
    @Test fun sweep_removes_retired_model_dirs_only_and_is_idempotent() {
        val root = Files.createTempDirectory("velum-retired").toFile()
        val qwen = java.io.File(root, "qwen3-4b-instruct-2507").apply { mkdirs() }
        java.io.File(qwen, "Qwen3-4B-Instruct-2507-Q4_K_M.gguf").writeBytes(ByteArray(10))
        java.io.File(qwen, "Qwen3-4B-Instruct-2507-Q4_K_M.gguf.partial").writeBytes(ByteArray(3))
        val gemma = java.io.File(root, "gemma-4-e2b").apply { mkdirs() }
        java.io.File(gemma, "gemma-4-E2B-it.litertlm").writeBytes(ByteArray(10))

        val store = ModelStoreDir(root.absolutePath)
        assertEquals(listOf("qwen3-4b-instruct-2507"), RetiredModels.sweep(store))
        assertTrue(!qwen.exists())
        assertTrue(java.io.File(gemma, "gemma-4-E2B-it.litertlm").exists(), "other models untouched")
        assertEquals(emptyList(), RetiredModels.sweep(store))
        root.deleteRecursively()
    }
}
