package dev.velum.core.templates

/**
 * Lazy classpath reader for the gzipped full Plaud JSONL
 * (`plaud_templates_full.jsonl.gz`), used for Discover "All" import.
 */
internal expect object PlaudFullTemplateAsset {
    /** Gunzipped JSONL text, or empty when the asset is missing / unsupported. */
    fun readJsonl(): String
}
