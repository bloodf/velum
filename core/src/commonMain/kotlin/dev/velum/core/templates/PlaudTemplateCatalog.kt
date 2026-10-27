package dev.velum.core.templates

import kotlinx.serialization.json.Json

/**
 * Plaud official + community templates shipped as built-ins.
 * Source JSONL: `docs/templates/plaud_templates.jsonl` → classpath `plaud_templates.jsonl`
 * via [PlaudTemplateAsset] (lazy — not embedded in a Kotlin string).
 *
 * Author / official stay in the data for [toCreditsMarkdown]; the app UI must not show them.
 */
object PlaudTemplateCatalog {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    val All: List<TemplateCatalogEntry> by lazy { parseJsonl(PlaudTemplateAsset.readJsonl()) }

    fun parseJsonl(text: String): List<TemplateCatalogEntry> {
        if (text.isBlank()) return emptyList()
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line ->
                val entry = json.decodeFromString(TemplateCatalogEntry.serializer(), line)
                normalizePlaudEntry(entry)
            }
            .toList()
    }

    fun toCreditsMarkdown(entries: List<TemplateCatalogEntry> = All): String {
        val sb = StringBuilder()
        sb.appendLine("# Template credits")
        sb.appendLine()
        sb.appendLine(
            "Generated from the built-in catalog. Plaud author / official fields are documented here " +
                "for provenance; the Velum app UI does **not** show Plaud author names or official badges. " +
                "In-app credit appears only for Velum PR contributions that set `contributor`.",
        )
        sb.appendLine()
        sb.appendLine("## Velum originals")
        sb.appendLine()
        sb.appendLine("| id | name | author | source |")
        sb.appendLine("|---|---|---|---|")
        BuiltinTemplateCatalog.velumEntries().forEach { e ->
            sb.appendLine("| `${e.id}` | ${e.name} | ${e.author} | ${e.source} |")
        }
        sb.appendLine()
        sb.appendLine("## Velum PR contributions")
        sb.appendLine()
        val contributed = VelumContributedCatalog.All
        if (contributed.isEmpty()) {
            sb.appendLine("_None yet — see `CONTRIBUTING.md` (Template contributions)._")
        } else {
            sb.appendLine("| id | name | contributor | github |")
            sb.appendLine("|---|---|---|---|")
            contributed.forEach { e ->
                val c = e.contributor
                sb.appendLine(
                    "| `${e.id}` | ${e.name} | ${c?.name.orEmpty()} | ${c?.github.orEmpty()} |",
                )
            }
        }
        sb.appendLine()
        sb.appendLine("## Plaud templates (official + community)")
        sb.appendLine()
        if (entries.isEmpty()) {
            sb.appendLine(
                "_Awaiting collected JSONL — drop into `docs/templates/plaud_templates.jsonl` and regenerate._",
            )
        } else {
            sb.appendLine("Shipped entries: **${entries.size}**.")
            sb.appendLine()
            sb.appendLine("| id | name | author | official | category | source |")
            sb.appendLine("|---|---|---|---|---|---|")
            entries.sortedWith(
                compareBy({ !it.official }, { it.author.lowercase() }, { it.name.lowercase() }),
            ).forEach { e ->
                sb.appendLine(
                    "| `${e.id}` | ${e.name} | ${e.author} | ${e.official} | " +
                        "${e.category} | ${e.source} |",
                )
            }
            sb.appendLine()
            sb.appendLine("### Authors")
            sb.appendLine()
            entries.groupBy { it.author }
                .toSortedMap(String.CASE_INSENSITIVE_ORDER)
                .forEach { (author, list) ->
                    sb.appendLine("- **$author** — ${list.size} template(s)")
                }
        }
        sb.appendLine()
        return sb.toString()
    }

    internal fun mapCategoryKey(categoryId: String?, category: String): String {
        val fromId = when (categoryId?.lowercase()) {
            "cat_general", "general" -> "general"
            "cat_meeting", "meeting" -> "meeting"
            "cat_call", "call" -> "call"
            "cat_sales", "sales" -> "sales"
            "cat_interview", "interview" -> "interview"
            "cat_consulting", "consulting" -> "consulting"
            "cat_education", "education" -> "education"
            "cat_speech", "speech" -> "speech"
            "cat_medical", "medical" -> "medical"
            "cat_legal", "legal" -> "legal"
            "cat_financial", "financial" -> "financial"
            "cat_real_estate", "estate", "real_estate" -> "estate"
            "cat_construction", "construction" -> "construction"
            "cat_it_engineering", "it", "it_engineering" -> "it"
            "cat_functional", "functional", "extractive" -> "extractive"
            else -> null
        }
        if (fromId != null) return fromId
        return when (category.trim().lowercase()) {
            "general" -> "general"
            "meeting" -> "meeting"
            "call" -> "call"
            "sales" -> "sales"
            "interview" -> "interview"
            "consulting" -> "consulting"
            "education" -> "education"
            "speech" -> "speech"
            "medical" -> "medical"
            "legal" -> "legal"
            "financial" -> "financial"
            "real estate", "realestate", "estate" -> "estate"
            "construction" -> "construction"
            "it", "it & engineering", "it and engineering" -> "it"
            "functional", "extractive" -> "extractive"
            else -> category.trim().lowercase()
                .replace(Regex("[^a-z0-9]+"), "_")
                .trim('_')
                .ifBlank { "general" }
        }
    }

    private fun normalizePlaudEntry(entry: TemplateCatalogEntry): TemplateCatalogEntry {
        val id = entry.id.ifBlank {
            "plaud.${entry.sourceTemplateId ?: entry.name.lowercase().replace(Regex("[^a-z0-9]+"), "-")}"
        }
        val categoryKey = mapCategoryKey(entry.categoryId, entry.category)
        return entry.copy(
            id = id,
            categoryId = entry.categoryId ?: categoryKey,
            category = categoryKey,
            source = entry.source.ifBlank { TemplateCatalogEntry.SOURCE_PLAUD_COMMUNITY },
            attribution = entry.attribution.ifBlank {
                buildString {
                    append("By ${entry.author.ifBlank { "Unknown" }}")
                    if (entry.official) append(" · Plaud official")
                    else append(" · ${TemplateCatalogEntry.SOURCE_PLAUD_COMMUNITY}")
                    entry.sourceTemplateId?.let { append(" · id $it") }
                }
            },
            // Plaud rows never carry a Velum PR contributor.
            contributor = null,
        )
    }
}
