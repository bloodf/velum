package dev.velum.core.templates

/**
 * Legacy placeholder — the curated Plaud JSONL now ships as a classpath asset
 * (`plaud_templates.jsonl`) and is read lazily via [PlaudTemplateAsset].
 * Kept so older sync scripts / imports do not break; do not embed the catalog here.
 */
@Deprecated("Use PlaudTemplateAsset.readJsonl() / PlaudTemplateCatalog.All", ReplaceWith("PlaudTemplateCatalog.All"))
object PlaudTemplateCatalogData {
    const val JSONL: String = ""
}
