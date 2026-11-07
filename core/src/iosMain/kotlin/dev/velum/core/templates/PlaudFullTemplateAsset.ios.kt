package dev.velum.core.templates

internal actual object PlaudFullTemplateAsset {
    actual fun readJsonl(): String = BundledCatalogAsset.readJsonl("plaud_templates_full.jsonl.gz")

    actual fun forEachLine(action: (String) -> Unit) {
        BundledCatalogAsset.forEachLine("plaud_templates_full.jsonl.gz", action = action)
    }
}
