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

    private fun where(
        filter: DiscoverFilter,
        ftsMatch: String?,
        includeCategory: Boolean,
        includeTags: Boolean,
        includeLang: Boolean,
    ): Pair<String, List<String>> {
        val clauses = mutableListOf(tierClause(filter.tier))
        val args = mutableListOf<String>()
        val q = filter.search.trim()
        if (q.isNotEmpty()) {
            val parts = mutableListOf<String>()
            if (ftsMatch != null) {
                parts += "id IN (SELECT template_id FROM template_fts WHERE template_fts MATCH ?)"
                args += ftsMatch
            }
            for (col in listOf("name", "description", "category", "author", "system_prompt", "tags")) {
                parts += "$col LIKE ? ESCAPE '\\'"
                args += like(q)
            }
            clauses += parts.joinToString(" OR ", "(", ")")
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

    /** Page of templates, most used first; ties broken by name then id so pages never overlap. */
    fun page(filter: DiscoverFilter, ftsMatch: String?): Built {
        val (w, args) = where(filter, ftsMatch, true, true, true)
        return Built(
            "SELECT id, name, category, description, author, system_prompt, uses, builtin, created_at, " +
                "featured, tags, lang, preferred_model, preferred_endpoint_profile_id FROM template " +
                "WHERE $w ORDER BY uses DESC, name ASC, id ASC LIMIT ? OFFSET ?",
            args,
        )
    }

    fun count(filter: DiscoverFilter, ftsMatch: String?): Built {
        val (w, args) = where(filter, ftsMatch, true, true, true)
        return Built("SELECT COUNT(*) FROM template WHERE $w", args)
    }

    fun categories(filter: DiscoverFilter, ftsMatch: String?): Built {
        val (w, args) = where(filter, ftsMatch, false, false, false)
        return Built("SELECT DISTINCT category FROM template WHERE $w", args)
    }

    /** Distinct raw `tags` column values within the category scope (decoded by the caller). */
    fun tagColumns(filter: DiscoverFilter, ftsMatch: String?): Built {
        val (w, args) = where(filter, ftsMatch, true, false, false)
        return Built("SELECT DISTINCT tags FROM template WHERE $w AND tags <> ''", args)
    }

    fun langs(filter: DiscoverFilter, ftsMatch: String?): Built {
        val (w, args) = where(filter, ftsMatch, true, false, false)
        return Built("SELECT DISTINCT lower(trim(lang)) FROM template WHERE $w", args)
    }
}
