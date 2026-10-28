package dev.velum.core.templates

/**
 * Discoverability filters over template rows that expose tags + lang.
 * Multi-select tags use AND (every selected tag must be present).
 */
object TemplateTagFilter {
    data class Row(
        val id: String,
        val tags: List<String>,
        val lang: String,
        val category: String = "",
    )

    fun filter(
        items: List<Row>,
        selectedTags: Set<String> = emptySet(),
        selectedLang: String? = null,
        selectedCategory: String? = null,
    ): List<Row> {
        val tags = selectedTags.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val lang = selectedLang?.trim()?.takeIf { it.isNotEmpty() && it != "all" && it != "und" }
        val cat = selectedCategory?.trim()?.takeIf { it.isNotEmpty() }
        return items.filter { row ->
            if (cat != null && row.category != cat) return@filter false
            if (lang != null) {
                val rowLang = row.lang.trim().lowercase().ifBlank { "und" }
                if (rowLang != lang.lowercase()) return@filter false
            }
            if (tags.isNotEmpty()) {
                val rowTags = row.tags.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                if (!rowTags.containsAll(tags)) return@filter false
            }
            true
        }
    }

    fun tagsPresentIn(items: List<Row>): List<String> =
        items.flatMap { it.tags }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()

    fun langsPresentIn(items: List<Row>): List<String> =
        items.map { it.lang.trim().lowercase().ifBlank { "und" } }
            .distinct()
            .sorted()
}
