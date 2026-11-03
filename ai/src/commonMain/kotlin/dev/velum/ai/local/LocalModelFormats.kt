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
