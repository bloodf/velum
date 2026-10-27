package dev.velum.core.templates

import kotlinx.serialization.json.Json

/**
 * Plaud official + community templates shipped as built-ins (with author credit).
 * Source JSONL: docs/templates/plaud_templates.jsonl → [PlaudTemplateCatalogData.JSONL].
 *
 * Empty until Heitor's collected dump is committed. Parser + seed path are ready.
 */
object PlaudTemplateCatalog {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    val All: List<TemplateCatalogEntry> by lazy { parseJsonl(PlaudTemplateCatalogData.JSONL) }

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
        sb.appendLine("Generated from the built-in catalog. Plaud templates ship with author attribution.")
        sb.appendLine()
        sb.appendLine("## Velum originals")
        sb.appendLine()
        sb.appendLine("| id | name | author | source |")
        sb.appendLine("|---|---|---|---|")
        BuiltinTemplateCatalog.velumEntries().forEach { e ->
            sb.appendLine("| `${e.id}` | ${e.name} | ${e.author} | ${e.source} |")
        }
        sb.appendLine()
        sb.appendLine("## Plaud templates (official + community)")
        sb.appendLine()
        if (entries.isEmpty()) {
            sb.appendLine("_Awaiting collected JSONL from Heitor — drop into `docs/templates/plaud_templates.jsonl` and regenerate._")
        } else {
            sb.appendLine("| id | name | author | official | sourceTemplateId | source |")
            sb.appendLine("|---|---|---|---|---|---|")
            entries.sortedWith(compareBy({ !it.official }, { it.author.lowercase() }, { it.name.lowercase() }))
                .forEach { e ->
                    sb.appendLine(
                        "| `${e.id}` | ${e.name} | ${e.author} | ${e.official} | " +
                            "${e.sourceTemplateId.orEmpty()} | ${e.source} |",
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

    private fun normalizePlaudEntry(entry: TemplateCatalogEntry): TemplateCatalogEntry {
        val id = entry.id.ifBlank {
            "plaud.${entry.sourceTemplateId ?: entry.name.lowercase().replace(Regex("[^a-z0-9]+"), "-")}"
        }
        return entry.copy(
            id = id,
            source = entry.source.ifBlank { TemplateCatalogEntry.SOURCE_PLAUD_COMMUNITY },
            attribution = entry.attribution.ifBlank {
                buildString {
                    append("By ${entry.author}")
                    if (entry.official) append(" · Plaud official")
                    else append(" · ${TemplateCatalogEntry.SOURCE_PLAUD_COMMUNITY}")
                    entry.sourceTemplateId?.let { append(" · id $it") }
                }
            },
        )
    }
}
