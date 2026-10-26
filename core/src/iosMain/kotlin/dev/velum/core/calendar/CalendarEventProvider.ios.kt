package dev.velum.core.calendar

actual fun createCalendarEventProvider(): CalendarEventProvider =
    object : CalendarEventProvider {
        override suspend fun currentOrNextEvent(aroundEpochMs: Long): CalendarLookupResult =
            CalendarLookupResult.Unavailable("Device calendar not wired on iOS yet")
    }
