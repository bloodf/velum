package dev.velum.core.autoflow

import dev.velum.core.templates.BundledCatalogAsset

internal actual object AutoFlowTemplateAsset {
    actual fun readJsonl(): String = BundledCatalogAsset.readJsonl("autoflow_templates.jsonl")
}
