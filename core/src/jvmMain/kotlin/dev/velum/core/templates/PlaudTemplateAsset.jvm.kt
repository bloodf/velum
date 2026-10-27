package dev.velum.core.templates

internal actual object PlaudTemplateAsset {
    actual fun readJsonl(): String {
        val stream = PlaudTemplateAsset::class.java.classLoader
            ?.getResourceAsStream(RESOURCE_NAME)
            ?: PlaudTemplateAsset::class.java.getResourceAsStream("/$RESOURCE_NAME")
            ?: return ""
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private const val RESOURCE_NAME = "plaud_templates.jsonl"
}
