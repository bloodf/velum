package dev.velum.core.search

/**
 * Builds a safe FTS5 MATCH expression from free-form user input.
 * Strips operators and uses prefix tokens so partial typing works.
 */
object FtsQuery {
    private val ftsKeywords = setOf("AND", "OR", "NOT", "NEAR")

    fun fromUserInput(raw: String): String? {
        val tokens = raw.trim()
            .split(Regex("\\s+"))
            .map { token ->
                buildString(token.length) {
                    for (ch in token) {
                        when {
                            ch.isLetterOrDigit() || ch == '_' -> append(ch)
                            else -> Unit
                        }
                    }
                }
            }
            .filter { it.isNotBlank() }
            .filter { it.uppercase() !in ftsKeywords }
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" ") { "$it*" }
    }
}
