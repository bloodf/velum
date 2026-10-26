package dev.velum.core.autoflow

/**
 * Pure matching for AutoFlow rules against a new recording.
 * Source filter `"all"` matches any source; otherwise equality on source.
 */
object AutoFlowMatcher {
    data class Rule(
        val enabled: Boolean,
        val wakePhrase: String?,
        val durationMinSeconds: Long,
        val durationMaxSeconds: Long,
        val sourceFilter: String,
        val templateId: String?,
    )

    data class Candidate(
        val source: String,
        val durationSeconds: Long,
        val title: String = "",
        val transcript: String? = null,
    )

    fun matches(rule: Rule, candidate: Candidate): Boolean {
        if (!rule.enabled) return false
        if (!sourceMatches(rule.sourceFilter, candidate.source)) return false
        if (candidate.durationSeconds < rule.durationMinSeconds) return false
        if (candidate.durationSeconds > rule.durationMaxSeconds) return false
        val phrase = rule.wakePhrase?.trim().orEmpty()
        if (phrase.isNotEmpty()) {
            val haystack = listOfNotNull(candidate.title, candidate.transcript)
                .joinToString("\n")
            if (!haystack.contains(phrase, ignoreCase = true)) return false
        }
        return true
    }

    fun sourceMatches(filter: String, source: String): Boolean {
        val normalized = filter.trim().lowercase()
        if (normalized.isEmpty() || normalized == "all") return true
        return normalized == source.trim().lowercase()
    }
}
