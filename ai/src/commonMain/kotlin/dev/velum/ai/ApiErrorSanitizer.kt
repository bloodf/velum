package dev.velum.ai

/**
 * Strips API key material from error text before it can land in WorkManager
 * Data, logs, or UI status. Prefer status/code only.
 */
object ApiErrorSanitizer {
    private val skPattern = Regex("""sk-[A-Za-z0-9_\-]{8,}""", RegexOption.IGNORE_CASE)
    private val bearerPattern = Regex("""Bearer\s+\S+""", RegexOption.IGNORE_CASE)
    private val apiKeyJsonPattern = Regex(
        """(?i)("?(?:api[_-]?key|access[_-]?token|authorization)"?\s*[:=]\s*)"?[^"\s,}]{8,}""",
    )

    fun sanitize(message: String?): String {
        if (message.isNullOrBlank()) return "request failed"
        var out = message
        out = skPattern.replace(out, "sk-***")
        out = bearerPattern.replace(out, "Bearer ***")
        out = apiKeyJsonPattern.replace(out) { mr -> "${mr.groupValues[1]}***" }
        return out.take(240)
    }

    fun httpFailure(methodPath: String, statusCode: Int): String =
        "$methodPath failed: HTTP $statusCode"
}
