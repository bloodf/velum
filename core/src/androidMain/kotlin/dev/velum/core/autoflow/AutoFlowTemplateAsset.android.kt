package dev.velum.core.autoflow

internal actual object AutoFlowTemplateAsset {
    actual fun readJsonl(): String {
        val stream = AutoFlowTemplateAsset::class.java.classLoader
            ?.getResourceAsStream(RESOURCE_NAME)
            ?: Thread.currentThread().contextClassLoader?.getResourceAsStream(RESOURCE_NAME)
            ?: AutoFlowTemplateAsset::class.java.getResourceAsStream("/$RESOURCE_NAME")
            ?: return ""
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private const val RESOURCE_NAME = "autoflow_templates.jsonl"
}
