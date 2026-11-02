package dev.velum.core.templates

/** Which slice of the catalog a Discover/My Space query runs over. */
enum class DiscoverTier { Featured, All, Mine }

/**
 * Composable Discover filters. Dimensions combine with AND; [tags] is multi-select and
 * every selected tag must be present (same contract as [TemplateTagFilter]). Blank, `all`
 * and `und` language selections are ignored: missing language is never matched as a language.
 */
data class DiscoverFilter(
    val tier: DiscoverTier = DiscoverTier.Featured,
    val search: String = "",
    val category: String? = null,
    val tags: Set<String> = emptySet(),
    val lang: String? = null,
) {
    val normalizedLang: String?
        get() = lang?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != "all" && it != "und" }

    val hasFacetSelection: Boolean
        get() = category != null || tags.isNotEmpty() || normalizedLang != null
}

/** Values available for the current tier + search (+ category for tags/langs), from catalog metadata only. */
data class DiscoverFacets(
    val categories: List<String>,
    val tags: List<String>,
    val langs: List<String>,
)

/** How a non-blank search is evaluated: FTS hits (ranked), or substring fallback when FTS is unavailable/empty/failed. */
enum class DiscoverSearchMode { Fts, Like }

/** Builds parameterized SQL so tier, search, category, tags and language are all pushed down to SQLite. */
object DiscoverTemplateQuery {
    class Built(val sql: String, val args: List<String>)

    fun tierClause(tier: DiscoverTier): String = when (tier) {
        DiscoverTier.Featured -> "builtin = 1 AND featured = 1"
        DiscoverTier.All -> "builtin = 1"
        DiscoverTier.Mine -> "builtin = 0"
    }

    private fun like(raw: String): String =
        "%" + raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

    /**
     * SQLite LIKE/lower() fold ASCII case only, so for each token also try its lower, upper and
     * capitalized forms (covers "água", "ÁGUA", "Água"). ponytail: mixed-case non-ASCII words
     * stored in odd casing can still miss; upgrade path is a normalized shadow column.
     */
    internal fun tokenVariants(token: String): List<String> {
        val lower = token.lowercase()
        return listOf(token, lower, token.uppercase(), lower.replaceFirstChar { it.uppercase() }).distinct()
    }

    private val searchColumns = listOf("name", "description", "category", "author", "system_prompt", "tags")

    private fun where(
        filter: DiscoverFilter,
        mode: DiscoverSearchMode?,
        ftsMatch: String?,
        includeCategory: Boolean,
        includeTags: Boolean,
        includeLang: Boolean,
    ): Pair<String, List<String>> {
        val clauses = mutableListOf(tierClause(filter.tier))
        val args = mutableListOf<String>()
        val tokens = filter.search.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isNotEmpty()) {
            if (mode == DiscoverSearchMode.Fts && ftsMatch != null) {
                clauses += "id IN (SELECT template_id FROM template_fts WHERE template_fts MATCH ?)"
                args += ftsMatch
            } else {
                // AND across tokens, OR across columns and case variants.
                for (token in tokens) {
                    val variants = tokenVariants(token)
                    val parts = searchColumns.flatMap { col ->
                        variants.map { v ->
                            args += like(v)
                            "$col LIKE ? ESCAPE '\\'"
                        }
                    }
                    clauses += parts.joinToString(" OR ", "(", ")")
                }
            }
        }
        val cat = filter.category?.trim()?.takeIf { it.isNotEmpty() }
        if (includeCategory && cat != null) {
            clauses += "category = ?"
            args += cat
        }
        val lang = filter.normalizedLang
        if (includeLang && lang != null) {
            clauses += "lower(trim(lang)) = ?"
            args += lang
        }
        if (includeTags) {
            for (tag in filter.tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct()) {
                clauses += "instr(',' || tags || ',', ',' || ? || ',') > 0"
                args += tag
            }
        }
        return clauses.joinToString(" AND ") to args
    }

    /**
     * Page of templates, most used first; in Fts mode ties follow FTS relevance, then name and id
     * so pages never overlap.
     */
    fun page(filter: DiscoverFilter, mode: DiscoverSearchMode?, ftsMatch: String?): Built {
        val (w, whereArgs) = where(filter, mode, ftsMatch, true, true, true)
        val ranked = mode == DiscoverSearchMode.Fts && ftsMatch != null && filter.search.isNotBlank()
        val cols = "template.id, template.name, template.category, template.description, template.author, " +
            "template.system_prompt, template.uses, template.builtin, template.created_at, " +
            "template.featured, template.tags, template.lang, template.preferred_model, " +
            "template.preferred_endpoint_profile_id"
        return if (ranked) {
            Built(
                "SELECT $cols FROM template LEFT JOIN (SELECT template_id, rank AS r FROM template_fts " +
                    "WHERE template_fts MATCH ?) f ON f.template_id = template.id " +
                    "WHERE $w ORDER BY template.uses DESC, f.r ASC, template.name ASC, template.id ASC LIMIT ? OFFSET ?",
                listOf(ftsMatch!!) + whereArgs,
            )
        } else {
            Built(
                "SELECT $cols FROM template WHERE $w " +
                    "ORDER BY template.uses DESC, template.name ASC, template.id ASC LIMIT ? OFFSET ?",
                whereArgs,
            )
        }
    }

    fun count(filter: DiscoverFilter, mode: DiscoverSearchMode?, ftsMatch: String?): Built {
        val (w, args) = where(filter, mode, ftsMatch, true, true, true)
        return Built("SELECT COUNT(*) FROM template WHERE $w", args)
    }

    fun categories(filter: DiscoverFilter, mode: DiscoverSearchMode?, ftsMatch: String?): Built {
        val (w, args) = where(filter, mode, ftsMatch, false, false, false)
        return Built("SELECT DISTINCT category FROM template WHERE $w", args)
    }

    /** Distinct raw `tags` column values within the category scope (decoded by the caller). */
    fun tagColumns(filter: DiscoverFilter, mode: DiscoverSearchMode?, ftsMatch: String?): Built {
        val (w, args) = where(filter, mode, ftsMatch, true, false, false)
        return Built("SELECT DISTINCT tags FROM template WHERE $w AND tags <> ''", args)
    }

    fun langs(filter: DiscoverFilter, mode: DiscoverSearchMode?, ftsMatch: String?): Built {
        val (w, args) = where(filter, mode, ftsMatch, true, false, false)
        return Built("SELECT DISTINCT lower(trim(lang)) FROM template WHERE $w", args)
    }
}
