package dev.velum.core.search

/**
 * Case-insensitive substring filter used by Files and Templates search UI.
 */
object ListSearch {
    fun <T> filter(
        items: List<T>,
        query: String,
        fields: (T) -> List<String?>,
    ): List<T> {
        val q = query.trim()
        if (q.isEmpty()) return items
        return items.filter { item ->
            fields(item).any { field ->
                field?.contains(q, ignoreCase = true) == true
            }
        }
    }
}
