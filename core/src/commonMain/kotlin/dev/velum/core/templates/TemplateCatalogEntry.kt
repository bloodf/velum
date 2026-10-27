package dev.velum.core.templates

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Unified built-in catalog row for Velum originals and Plaud (official + community).
 *
 * Plaud dump lands as JSONL (one object per line) under docs/templates/plaud_templates.jsonl
 * and is mirrored into [PlaudTemplateCatalogData] for on-device seed.
 */
@Serializable
enum class TemplateBodyType {
    @SerialName("prompt")
    Prompt,

    @SerialName("outline")
    Outline,
}

@Serializable
data class TemplateCatalogEntry(
    val id: String,
    val categoryId: String? = null,
    val category: String,
    val name: String,
    val author: String,
    val sourceTemplateId: String? = null,
    val official: Boolean = false,
    val description: String = "",
    val bodyType: TemplateBodyType = TemplateBodyType.Prompt,
    val body: String,
    /** e.g. "Velum" or "Plaud template community". */
    val source: String = SOURCE_VELUM,
    /** Human-readable credit line shown in picker/detail. */
    val attribution: String = "",
    val nameEn: String? = null,
    val namePtBr: String? = null,
    val uses: Long = 0,
) {
    fun displayName(languageTag: String): String {
        val lang = languageTag.lowercase().substringBefore("-").substringBefore("_")
        return when {
            lang == "en" && !nameEn.isNullOrBlank() -> nameEn
            lang != "en" && !namePtBr.isNullOrBlank() -> namePtBr
            else -> name
        }
    }

    fun creditLine(): String {
        val base = attribution.ifBlank {
            buildString {
                append(author)
                if (source.isNotBlank() && source != author) {
                    append(" · ")
                    append(source)
                }
                if (official) append(" · official")
            }
        }
        return base
    }

    fun systemPrompt(): String = when (bodyType) {
        TemplateBodyType.Prompt -> body
        TemplateBodyType.Outline ->
            "You will receive a transcript of a recording. Write a Markdown summary using this outline as section guidance. Omit empty sections. Rely only on the transcript.\n\nOutline:\n$body"
    }

    companion object {
        const val SOURCE_VELUM: String = "Velum"
        const val SOURCE_PLAUD_COMMUNITY: String = "Plaud template community"
    }
}
