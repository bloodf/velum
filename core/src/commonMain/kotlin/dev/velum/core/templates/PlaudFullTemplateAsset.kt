package dev.velum.core.templates

/**
 * Lazy classpath reader for the gzipped full Plaud JSONL
 * (`plaud_templates_full.jsonl.gz`), used for Discover "All" import.
 */
internal expect object PlaudFullTemplateAsset {
    /** Gunzipped JSONL text, or empty when the asset is missing / unsupported. */
    fun readJsonl(): String

    /**
     * Stream gunzipped JSONL lines without materializing the full ~29MB String.
     * Skips blank lines and `#` comments. No-op when the asset is missing.
     */
    fun forEachLine(action: (String) -> Unit)
}
