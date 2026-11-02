package dev.velum.core.search

/**
 * Case- and accent-insensitive substring filter used by Files and Templates search UI.
 */
object ListSearch {
    fun <T> filter(
        items: List<T>,
        query: String,
        fields: (T) -> List<String?>,
    ): List<T> {
        val q = query.trim()
        if (q.isEmpty()) return items
        val folded = fold(q)
        return items.filter { item ->
            fields(item).any { field ->
                field != null && (field.contains(q, ignoreCase = true) || fold(field).contains(folded))
            }
        }
    }

    // Latin diacritics only (pt/es/fr/de): common code has no Unicode normalizer.
    private const val ACCENTED = "àáâãäåçèéêëìíîïñòóôõöùúûüýÿ"
    private const val PLAIN = "aaaaaaceeeeiiiinooooouuuuyy"

    /** Lowercase and strip common Latin diacritics, so "Reunião" and "reuniao" compare equal. */
    internal fun fold(text: String): String {
        val lower = text.lowercase()
        if (lower.none { it in ACCENTED }) return lower
        return buildString(lower.length) {
            for (ch in lower) {
                val i = ACCENTED.indexOf(ch)
                append(if (i >= 0) PLAIN[i] else ch)
            }
        }
    }
}
