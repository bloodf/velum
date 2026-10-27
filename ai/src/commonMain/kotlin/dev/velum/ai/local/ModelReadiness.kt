package dev.velum.ai.local

/**
 * Marks a downloaded model Ready only when expected weight files exist under models/<id>/.
 */
object ModelReadiness {
    fun isReady(spec: LocalModelSpec, modelDir: ModelStoreDir): Boolean =
        missingRequirements(spec, modelDir).isEmpty()

    fun missingRequirements(spec: LocalModelSpec, modelDir: ModelStoreDir): List<String> {
        if (!modelDir.exists() || !modelDir.isDirectory()) {
            return listOf("model directory missing: ${modelDir.absolutePath}")
        }
        val archive = modelDir.resolve(spec.archiveFileName)
        val layout = layoutFor(spec)
        when (layout) {
            is ModelLayout.OpaqueFile,
            is ModelLayout.SingleOnnxFile,
            is ModelLayout.SingleLlmFile,
            is ModelLayout.SingleCactFile,
            -> {
                return if (archive.exists() && archive.length() > 0L) {
                    emptyList()
                } else {
                    listOf("expected file ${spec.archiveFileName}")
                }
            }
            else -> Unit
        }
        val root = resolveWeightsDir(modelDir, spec)
            ?: return listOf(
                "extracted weights not found under ${modelDir.absolutePath} " +
                    "(need ${layout.describe()})",
            )
        return layout.missing(root)
    }

    /**
     * Directory that contains the ONNX / LiteRT weights (may be a nested extract folder).
     */
    fun resolveWeightsDir(modelDir: ModelStoreDir, spec: LocalModelSpec): ModelStoreDir? {
        val layout = layoutFor(spec)
        when (layout) {
            is ModelLayout.OpaqueFile,
            is ModelLayout.SingleOnnxFile,
            is ModelLayout.SingleLlmFile,
            is ModelLayout.SingleCactFile,
            -> {
                return if (modelDir.resolve(spec.archiveFileName).exists()) modelDir else null
            }
            else -> Unit
        }
        return modelDir.walkDirectories().firstOrNull { layout.missing(it).isEmpty() }
    }

    fun layoutFor(spec: LocalModelSpec): ModelLayout {
        val name = spec.archiveFileName.lowercase()
        // Single-file catalog artifacts (.onnx embeddings, .litertlm / .gguf LLMs).
        if (!spec.usesArchiveExtract()) {
            return when {
                name.endsWith(".onnx") -> ModelLayout.SingleOnnxFile(spec.archiveFileName)
                name.endsWith(".cact") -> ModelLayout.SingleCactFile(spec.archiveFileName)
                name.endsWith(".litertlm") || name.endsWith(".gguf") ||
                    name.endsWith(".task") || name.endsWith(".tflite") ->
                    ModelLayout.SingleLlmFile(spec.archiveFileName)
                else -> ModelLayout.OpaqueFile
            }
        }
        return when (spec.kind) {
            LocalModelKind.Asr -> when {
                spec.id.contains("whisper", ignoreCase = true) -> ModelLayout.Whisper
                spec.id.contains("sensevoice", ignoreCase = true) ||
                    spec.id.contains("sense-voice", ignoreCase = true) -> ModelLayout.SenseVoice
                else -> ModelLayout.Transducer
            }
            LocalModelKind.Diarization, LocalModelKind.Embedding -> ModelLayout.SingleOnnx
            LocalModelKind.Llm -> ModelLayout.LlmWeights
        }
    }
}

sealed class ModelLayout {
    abstract fun describe(): String
    abstract fun missing(dir: ModelStoreDir): List<String>

    data object Transducer : ModelLayout() {
        override fun describe(): String =
            "tokens.txt + encoder*.onnx + decoder*.onnx + joiner*.onnx"

        override fun missing(dir: ModelStoreDir): List<String> {
            val files = dir.listChildren().filter { !it.isDirectory() }.map { it.name.lowercase() }
            val missing = mutableListOf<String>()
            if (files.none { it == "tokens.txt" || it.endsWith("-tokens.txt") }) {
                missing += "tokens.txt"
            }
            if (files.none { it.contains("encoder") && it.endsWith(".onnx") }) {
                missing += "encoder*.onnx"
            }
            if (files.none { it.contains("decoder") && it.endsWith(".onnx") }) {
                missing += "decoder*.onnx"
            }
            if (files.none { it.contains("joiner") && it.endsWith(".onnx") }) {
                missing += "joiner*.onnx"
            }
            return missing
        }
    }

    data object Whisper : ModelLayout() {
        override fun describe(): String =
            "tokens + encoder*.onnx + decoder*.onnx"

        override fun missing(dir: ModelStoreDir): List<String> {
            val files = dir.listChildren().filter { !it.isDirectory() }.map { it.name.lowercase() }
            val missing = mutableListOf<String>()
            if (files.none { it.contains("tokens") && it.endsWith(".txt") }) {
                missing += "*tokens*.txt"
            }
            if (files.none { it.contains("encoder") && (it.endsWith(".onnx") || it.endsWith(".weights")) }) {
                missing += "encoder*.onnx"
            }
            if (files.none { it.contains("decoder") && it.endsWith(".onnx") }) {
                missing += "decoder*.onnx"
            }
            return missing
        }
    }

    data object SenseVoice : ModelLayout() {
        override fun describe(): String = "tokens.txt + model*.onnx"

        override fun missing(dir: ModelStoreDir): List<String> {
            val files = dir.listChildren().filter { !it.isDirectory() }.map { it.name.lowercase() }
            val missing = mutableListOf<String>()
            if (files.none { it == "tokens.txt" || it.endsWith("-tokens.txt") }) {
                missing += "tokens.txt"
            }
            if (files.none { it.startsWith("model") && it.endsWith(".onnx") }) {
                missing += "model*.onnx"
            }
            return missing
        }
    }

    data object SingleOnnx : ModelLayout() {
        override fun describe(): String = "*.onnx"

        override fun missing(dir: ModelStoreDir): List<String> {
            val files = dir.walkFiles().map { it.name.lowercase() }
            return if (files.any { it.endsWith(".onnx") }) emptyList()
            else listOf("*.onnx")
        }
    }

    /** Direct download of one embedding / segmentation .onnx (no archive extract). */
    data class SingleOnnxFile(val fileName: String) : ModelLayout() {
        override fun describe(): String = fileName
        override fun missing(dir: ModelStoreDir): List<String> {
            val f = dir.resolve(fileName)
            return if (f.exists() && f.length() > 0L) emptyList() else listOf(fileName)
        }
    }

    data object LlmWeights : ModelLayout() {
        override fun describe(): String = "*.litertlm / *.task / *.gguf / *.bin"

        override fun missing(dir: ModelStoreDir): List<String> {
            val files = dir.walkFiles().map { it.name.lowercase() }
            val ok = files.any {
                it.endsWith(".litertlm") || it.endsWith(".task") ||
                    it.endsWith(".gguf") || it.endsWith(".bin") ||
                    it.endsWith(".tflite")
            }
            return if (ok) emptyList() else listOf(describe())
        }
    }

    /** Direct download of one LLM weight file (.litertlm / .gguf / …). */
    data class SingleLlmFile(val fileName: String) : ModelLayout() {
        override fun describe(): String = fileName
        override fun missing(dir: ModelStoreDir): List<String> {
            val f = dir.resolve(fileName)
            return if (f.exists() && f.length() > 0L) emptyList() else listOf(fileName)
        }
    }

    /** Needle/Whistle `.cact` single-file weights (no archive extract). */
    data class SingleCactFile(val fileName: String) : ModelLayout() {
        override fun describe(): String = fileName
        override fun missing(dir: ModelStoreDir): List<String> {
            val f = dir.resolve(fileName)
            return if (f.exists() && f.length() > 0L) emptyList() else listOf(fileName)
        }
    }

    /** Non-archive catalog artifacts used in unit tests / opaque blobs. */
    data object OpaqueFile : ModelLayout() {
        override fun describe(): String = "raw artifact file"
        override fun missing(dir: ModelStoreDir): List<String> = emptyList()
    }
}

internal fun LocalModelSpec.usesArchiveExtract(): Boolean =
    ModelArchiveExtractor.isArchive(archiveFileName)
