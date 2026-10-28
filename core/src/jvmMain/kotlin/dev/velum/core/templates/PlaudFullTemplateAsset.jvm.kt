package dev.velum.core.templates

import java.util.zip.GZIPInputStream

internal actual object PlaudFullTemplateAsset {
    actual fun readJsonl(): String {
        val stream = openGzipStream() ?: return ""
        return stream.use { raw ->
            GZIPInputStream(raw).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
    }

    actual fun forEachLine(action: (String) -> Unit) {
        val stream = openGzipStream() ?: return
        stream.use { raw ->
            GZIPInputStream(raw).bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                        action(trimmed)
                    }
                }
            }
        }
    }

    private fun openGzipStream() =
        PlaudFullTemplateAsset::class.java.classLoader
            ?.getResourceAsStream(RESOURCE_NAME)
            ?: PlaudFullTemplateAsset::class.java.getResourceAsStream("/$RESOURCE_NAME")

    private const val RESOURCE_NAME = "plaud_templates_full.jsonl.gz"
}
