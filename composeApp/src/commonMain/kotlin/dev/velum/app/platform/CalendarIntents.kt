package dev.velum.app.platform

/** Open a local calendar insert editor (ACTION_INSERT). No OAuth. */
expect fun openCalendarInsert(title: String, description: String): CalendarIntentResult

sealed class CalendarIntentResult {
    data object Launched : CalendarIntentResult()
    data class Blocked(val message: String) : CalendarIntentResult()
}
