package dev.velum.core.templates

/**
 * Lazy classpath/asset reader for the curated Plaud JSONL.
 * Keeps the ~2.5 MB catalog out of a Kotlin string constant so cold start
 * does not pay for class-init of a giant literal.
 */
internal expect object PlaudTemplateAsset {
    /** Raw JSONL text, or empty when the asset is missing. */
    fun readJsonl(): String
}
