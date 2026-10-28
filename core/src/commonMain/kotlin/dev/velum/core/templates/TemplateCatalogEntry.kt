package dev.velum.core.templates

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Unified built-in catalog row for Velum originals, Velum PR contributions, and Plaud.
 *
 * Plaud dump: `docs/templates/plaud_templates.jsonl` → classpath asset `plaud_templates.jsonl`.
 * Velum PR contributions: `docs/templates/velum_contributed.jsonl` (optional `contributor`).
 *
 * Plaud `author` / `official` are kept for `docs/TEMPLATES_CREDITS.md` only — the app UI
 * must not surface them. UI credit is only [contributor] (Velum PR contributions).
 */
@Serializable
enum class TemplateBodyType {
    @SerialName("prompt")
    Prompt,

    @SerialName("outline")
    Outline,
}

/** Velum PR contributor credit (shown on template detail when present). */
@Serializable
data class TemplateContributor(
    val name: String,
    /** GitHub handle without leading `@`. */
    val github: String? = null,
) {
    fun creditLine(): String {
        val handle = github?.trim()?.removePrefix("@").orEmpty()
        return if (handle.isEmpty()) name.trim()
        else "${name.trim()} (@$handle)"
    }
}

@Serializable
data class TemplateCatalogEntry(
    val id: String,
    val categoryId: String? = null,
    val category: String,
    val name: String,
    val author: String = "",
    val sourceTemplateId: String? = null,
    val official: Boolean = false,
    val description: String = "",
    val bodyType: TemplateBodyType = TemplateBodyType.Prompt,
    val body: String,
    /** e.g. "Velum" or "Plaud template community". */
    val source: String = SOURCE_VELUM,
    /** Docs-only credit line for Plaud / provenance — not shown in the app UI. */
    val attribution: String = "",
    val nameEn: String? = null,
    val namePtBr: String? = null,
    @SerialName("usageCount")
    val uses: Long = 0,
    /**
     * Featured catalog rows default true (classpath Featured JSONL).
     * Full catalog marks non-featured rows false for Discover "All" import.
     */
    @SerialName("featured")
    val featured: Boolean = true,
    /** BCP-47-ish language from hygiene (`en`, `pt`, `und`, …). */
    val lang: String = "und",
    /** Controlled vocabulary slugs from hygiene (`tools/templates/tags.py`). */
    val tags: List<String> = emptyList(),
    /**
     * Optional Velum PR contributor. When set, template detail shows this credit.
     * Never set for copied Plaud templates.
     */
    val contributor: TemplateContributor? = null,
    /** Optional preferred OpenAI-compatible model id when summarizing with this template. */
    val preferredModel: String? = null,
    /** Optional endpoint profile id for [preferredModel]. */
    val preferredEndpointProfileId: String? = null,
) {
    fun displayName(languageTag: String): String {
        val lang = languageTag.lowercase().substringBefore("-").substringBefore("_")
        return when {
            lang == "en" && !nameEn.isNullOrBlank() -> nameEn
            lang != "en" && !namePtBr.isNullOrBlank() -> namePtBr
            else -> name
        }
    }

    /** Docs / credits markdown only — not for picker/detail UI. */
    fun docsCreditLine(): String {
        val base = attribution.ifBlank {
            buildString {
                append(author.ifBlank { "Unknown" })
                if (source.isNotBlank() && source != author) {
                    append(" · ")
                    append(source)
                }
                if (official) append(" · official")
            }
        }
        return base
    }

    /** App UI credit — only Velum PR [contributor], never Plaud author/official. */
    fun uiContributorCredit(): String? =
        contributor?.creditLine()?.takeIf { it.isNotBlank() }

    /**
     * Prompt-type body = output skeleton the model should fill.
     * Outline-type body = free-form instructions for the summary.
     */
    fun systemPrompt(): String = when (bodyType) {
        TemplateBodyType.Prompt ->
            "You will receive a transcript of a recording. Write a Markdown summary that fills this output skeleton. " +
                "Populate every applicable section from the transcript; omit sections with no supporting material; " +
                "never invent facts.\n\nSkeleton:\n$body"
        TemplateBodyType.Outline ->
            "You will receive a transcript of a recording. Follow these instructions to write the Markdown summary. " +
                "Rely only on the transcript; never invent facts.\n\nInstructions:\n$body"
    }

    companion object {
        const val SOURCE_VELUM: String = "Velum"
        const val SOURCE_PLAUD_COMMUNITY: String = "Plaud template community"
    }
}
