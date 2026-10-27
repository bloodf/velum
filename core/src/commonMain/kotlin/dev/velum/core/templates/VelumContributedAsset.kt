package dev.velum.core.templates

/** Lazy classpath reader for `velum_contributed.jsonl` (Velum PR templates). */
internal expect object VelumContributedAsset {
    fun readJsonl(): String
}
