package dev.velum.core.templates

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * User-supplied local template library export (NOT Plaud cloud text).
 *
 * Expected JSON shape (array or `{ "templates": [ ... ] }`):
 * ```json
 * {
 *   "templates": [
 *     {
 *       "name": "Standup",
 *       "category": "Meeting",
 *       "description": "Daily standup notes",
 *       "body": "## Wins\n- …",
 *       "author": "You"
 *     }
 *   ]
 * }
 * ```
 * [body] / [outline] / [systemPrompt] are accepted as the LLM instruction.
 */
@Serializable
data class TemplateExportFile(
    val templates: List<ImportedTemplateSpec> = emptyList(),
)

@Serializable
data class ImportedTemplateSpec(
    val id: String? = null,
    val name: String,
    val category: String = "General",
    @SerialName("categoryId")
    val categoryId: String? = null,
    val description: String = "",
    val body: String? = null,
    val outline: String? = null,
    @SerialName("system_prompt")
    val systemPrompt: String? = null,
    val author: String = "Import",
    val sourceTemplateId: String? = null,
    val official: Boolean = false,
    val bodyType: TemplateBodyType? = null,
    val source: String? = null,
    val attribution: String? = null,
) {
    fun resolvedPrompt(): String {
        val raw = body?.takeIf { it.isNotBlank() }
            ?: outline?.takeIf { it.isNotBlank() }
            ?: systemPrompt?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Template \"$name\" needs body, outline, or system_prompt")
        return when (bodyType) {
            TemplateBodyType.Outline ->
                "You will receive a transcript of a recording. Write a Markdown summary using this outline as section guidance. Omit empty sections. Rely only on the transcript.\n\nOutline:\n$raw"
            TemplateBodyType.Prompt, null -> raw
        }
    }

    fun creditLine(): String =
        attribution?.takeIf { it.isNotBlank() }
            ?: buildString {
                append(author)
                source?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                if (official) append(" · official")
            }
}

object TemplateJsonImporter {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parse(text: String): List<ImportedTemplateSpec> {
        val trimmed = text.trim()
        require(trimmed.isNotEmpty()) { "Empty template JSON" }
        return when {
            trimmed.startsWith("[") -> json.decodeFromString<List<ImportedTemplateSpec>>(trimmed)
            else -> {
                val file = json.decodeFromString<TemplateExportFile>(trimmed)
                if (file.templates.isNotEmpty()) {
                    file.templates
                } else {
                    // Single object without wrapper
                    listOf(json.decodeFromString<ImportedTemplateSpec>(trimmed))
                }
            }
        }
    }

    fun stableId(spec: ImportedTemplateSpec, index: Int): String {
        val raw = spec.id?.trim().orEmpty()
        if (raw.isNotEmpty()) return raw.take(80)
        val slug = spec.name.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(48)
            .ifBlank { "template" }
        return "user-import-$slug-$index"
    }
}
