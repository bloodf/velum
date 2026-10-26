package dev.velum.app.platform

actual fun openCalendarInsert(title: String, description: String): CalendarIntentResult =
    CalendarIntentResult.Blocked("Calendar insert not wired on iOS yet")
