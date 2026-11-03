package dev.velum.core.calendar

/**
 * Local device calendar (Android CalendarProvider). No Google/Plaud OAuth.
 */
data class CalendarEventSummary(
    val title: String,
    val beginEpochMs: Long,
    val endEpochMs: Long,
) {
    /**
     * True when the event is on at [epochMs]: it began (or begins within [graceMs] after) and has not ended. The end
     * is exclusive, so back-to-back meetings do not both claim the boundary instant.
     */
    fun isOnAt(epochMs: Long, graceMs: Long = DEFAULT_GRACE_MS): Boolean =
        beginEpochMs - graceMs <= epochMs && epochMs < endEpochMs

    companion object {
        const val DEFAULT_GRACE_MS = 5 * 60_000L
    }
}

sealed class CalendarLookupResult {
    data class Ok(val currentOrNext: CalendarEventSummary?) : CalendarLookupResult()
    data object PermissionDenied : CalendarLookupResult()
    data class Unavailable(val reason: String) : CalendarLookupResult()
}

interface CalendarEventProvider {
    /** Current or next event title around [aroundEpochMs], for naming a recording after sync. */
    suspend fun currentOrNextEvent(aroundEpochMs: Long): CalendarLookupResult
}

expect fun createCalendarEventProvider(): CalendarEventProvider
