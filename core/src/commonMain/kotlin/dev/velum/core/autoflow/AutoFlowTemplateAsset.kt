package dev.velum.core.autoflow

/**
 * Lazy classpath reader for bundled AutoFlow automation templates.
 * Source of truth: `templates/autoflow/<category>/<id>.json` packed by
 * `scripts/sync-autoflow-templates.sh` into `autoflow_templates.jsonl`.
 */
internal expect object AutoFlowTemplateAsset {
    /** Raw JSONL text, or empty when the asset is missing. */
    fun readJsonl(): String
}
