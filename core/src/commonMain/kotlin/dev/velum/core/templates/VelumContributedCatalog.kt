package dev.velum.core.templates

import kotlinx.serialization.json.Json

/**
 * Original templates contributed to Velum via pull request.
 * Source: `docs/templates/velum_contributed.jsonl` → classpath `velum_contributed.jsonl`.
 *
 * Each entry may set [TemplateCatalogEntry.contributor] (name + optional GitHub handle),
 * which is the only in-app author credit shown on template detail.
 */
object VelumContributedCatalog {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    val All: List<TemplateCatalogEntry> by lazy { parseJsonl(VelumContributedAsset.readJsonl()) }

    fun parseJsonl(text: String): List<TemplateCatalogEntry> {
        if (text.isBlank()) return emptyList()
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line ->
                val entry = json.decodeFromString(TemplateCatalogEntry.serializer(), line)
                normalize(entry)
            }
            .toList()
    }

    private fun normalize(entry: TemplateCatalogEntry): TemplateCatalogEntry {
        val id = entry.id.ifBlank {
            "velum.contrib.${entry.name.lowercase().replace(Regex("[^a-z0-9]+"), "-")}"
        }.let { raw ->
            if (raw.startsWith("velum.")) raw else "velum.contrib.$raw"
        }
        val categoryKey = PlaudTemplateCatalog.mapCategoryKey(entry.categoryId, entry.category)
        val contributor = entry.contributor?.takeIf { it.name.isNotBlank() }
        return entry.copy(
            id = id,
            categoryId = entry.categoryId ?: categoryKey,
            category = categoryKey,
            source = entry.source.ifBlank { TemplateCatalogEntry.SOURCE_VELUM },
            author = entry.author.ifBlank { contributor?.name ?: "Velum" },
            contributor = contributor,
            official = false,
        )
    }
}
