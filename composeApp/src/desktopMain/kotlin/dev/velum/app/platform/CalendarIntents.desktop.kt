package dev.velum.app.platform

actual fun openCalendarInsert(title: String, description: String): CalendarIntentResult =
    CalendarIntentResult.Blocked("Calendar insert is Android-only")
