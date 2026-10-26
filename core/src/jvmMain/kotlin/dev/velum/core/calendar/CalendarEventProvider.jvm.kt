package dev.velum.core.calendar

actual fun createCalendarEventProvider(): CalendarEventProvider =
    object : CalendarEventProvider {
        override suspend fun currentOrNextEvent(aroundEpochMs: Long): CalendarLookupResult =
            CalendarLookupResult.Unavailable("Device calendar is Android-only")
    }
