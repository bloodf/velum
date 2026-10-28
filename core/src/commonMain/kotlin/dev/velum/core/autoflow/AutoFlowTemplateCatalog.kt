package dev.velum.core.autoflow

import kotlinx.serialization.json.Json

/**
 * Built-in AutoFlow automation templates shipped as classpath JSONL.
 *
 * Source files: `templates/autoflow/<category>/<slug>.json`
 * Packed by: `scripts/sync-autoflow-templates.sh`
 */
object AutoFlowTemplateCatalog {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    val All: List<AutoFlowTemplate> by lazy { parseJsonl(AutoFlowTemplateAsset.readJsonl()) }

    fun byId(id: String): AutoFlowTemplate? = All.firstOrNull { it.id == id }

    fun categoriesPresent(templates: List<AutoFlowTemplate> = All): List<String> =
        AutoFlowTemplateCategories.Order.filter { key -> templates.any { it.category == key } }

    fun search(
        query: String,
        category: String? = null,
        languageTag: String = "pt-BR",
        templates: List<AutoFlowTemplate> = All,
    ): List<AutoFlowTemplate> {
        val scoped = if (category.isNullOrBlank()) {
            templates
        } else {
            templates.filter { it.category == category }
        }
        val q = query.trim()
        if (q.isEmpty()) return scoped
        val needle = q.lowercase()
        return scoped.filter { t ->
            t.id.contains(needle, ignoreCase = true) ||
                t.name.forLocale(languageTag).contains(needle, ignoreCase = true) ||
                t.description.forLocale(languageTag).contains(needle, ignoreCase = true) ||
                t.preview.forLocale(languageTag).contains(needle, ignoreCase = true) ||
                t.category.contains(needle, ignoreCase = true) ||
                t.author?.name?.contains(needle, ignoreCase = true) == true
        }
    }

    fun parseJsonl(text: String): List<AutoFlowTemplate> {
        if (text.isBlank()) return emptyList()
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line -> json.decodeFromString(AutoFlowTemplate.serializer(), line) }
            .toList()
    }
}
