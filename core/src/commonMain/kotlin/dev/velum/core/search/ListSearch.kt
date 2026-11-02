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
        val queryHasAccent = !folded.equals(q, ignoreCase = true)
        return items.filter { item ->
            fields(item).any { field -> field != null && matches(field, q, folded, queryHasAccent) }
        }
    }

    /** Allocates a folded copy only for fields that contain an accented letter (#100: 16k-row scans). */
    private fun matches(field: String, q: String, folded: String, queryHasAccent: Boolean): Boolean {
        if (field.contains(q, ignoreCase = true)) return true
        if (field.any { it.lowercaseChar() in ACCENTED }) return fold(field).contains(folded)
        // Unaccented field: folding it is a no-op, so only an accented query can still match.
        return queryHasAccent && field.contains(folded, ignoreCase = true)
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
