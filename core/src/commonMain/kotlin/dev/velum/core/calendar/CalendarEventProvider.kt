package dev.velum.core.calendar

/**
 * Local device calendar (Android CalendarProvider). No Google/Plaud OAuth.
 */
data class CalendarEventSummary(
    val title: String,
    val beginEpochMs: Long,
    val endEpochMs: Long,
)

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
