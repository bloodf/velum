package dev.velum.core.templates

internal actual object PlaudTemplateAsset {
    actual fun readJsonl(): String = BundledCatalogAsset.readJsonl("plaud_templates.jsonl")
}
