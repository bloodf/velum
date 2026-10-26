package dev.velum.core.search

/**
 * Builds a safe FTS5 MATCH expression from free-form user input.
 * Strips operators and uses prefix tokens so partial typing works.
 */
object FtsQuery {
    fun fromUserInput(raw: String): String? {
        val tokens = raw.trim()
            .split(Regex("\\s+"))
            .map { it.replace(Regex("[\"'*^:]"), "") }
            .filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" ") { "$it*" }
    }
}
