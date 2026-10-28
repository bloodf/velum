package dev.velum.core.templates

import java.util.zip.GZIPInputStream

internal actual object PlaudFullTemplateAsset {
    actual fun readJsonl(): String {
        val stream = PlaudFullTemplateAsset::class.java.classLoader
            ?.getResourceAsStream(RESOURCE_NAME)
            ?: Thread.currentThread().contextClassLoader?.getResourceAsStream(RESOURCE_NAME)
            ?: PlaudFullTemplateAsset::class.java.getResourceAsStream("/$RESOURCE_NAME")
            ?: return ""
        return stream.use { raw ->
            GZIPInputStream(raw).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
    }

    private const val RESOURCE_NAME = "plaud_templates_full.jsonl.gz"
}
