package dev.velum.ai.local

/**
 * Artifact formats the shipping runtimes can actually load (#273). The Android LLM runtime is LiteRT-LM, which loads
 * `.litertlm` and `.task` bundles; it cannot load GGUF or bare `.tflite`. `CatalogFormatsTest` ties every catalog entry to
 * these lists, so a catalog entry no runtime can load cannot be added.
 */
object LocalModelFormats {
    val llmWeights: List<String> = listOf(".litertlm", ".task")

    /** Non-LLM artifacts: sherpa-onnx archives, single ONNX embedding models, Whistle's `.cact`. */
    val otherArtifacts: List<String> = listOf(".tar.bz2", ".onnx", ".cact")

    fun isLoadableLlmWeights(fileName: String): Boolean = llmWeights.any { fileName.lowercase().endsWith(it) }

    fun isLoadable(spec: LocalModelSpec): Boolean {
        val name = spec.archiveFileName.lowercase()
        return if (spec.kind == LocalModelKind.Llm) isLoadableLlmWeights(name) else otherArtifacts.any { name.endsWith(it) }
    }
}

/**
 * Models that were offered once and no longer are. A device that downloaded one keeps its weights (about 2.5 GB for
 * Qwen3 GGUF, #273) in `models/<id>` with no UI to remove it, so the app deletes these directories at startup.
 * Iterating is idempotent: once gone, nothing is left to do. Call off the main thread.
 *
 * Local LLMs run only on Android (LiteRT-LM); the iOS and JVM runtimes throw UnsupportedOperationException, so no
 * platform other than Android has an LLM format to load.
 */
object RetiredModels {
    val ids: List<String> = listOf("qwen3-4b-instruct-2507")

    /** Deletes each retired model directory under [storeRoot]; returns the ids that were removed. */
    fun sweep(storeRoot: ModelStoreDir): List<String> = ids.filter { id ->
        val dir = storeRoot.resolve(id)
        if (dir.exists()) {
            dir.deleteRecursively()
            true
        } else {
            false
        }
    }
}
