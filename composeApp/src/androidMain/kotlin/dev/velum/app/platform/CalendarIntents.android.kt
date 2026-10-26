package dev.velum.app.platform

import android.content.Intent
import android.provider.CalendarContract

actual fun openCalendarInsert(title: String, description: String): CalendarIntentResult {
    val context = ShareTextHolder.context
        ?: return CalendarIntentResult.Blocked("No Android context for calendar intent")
    return try {
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title)
            putExtra(CalendarContract.Events.DESCRIPTION, description)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        CalendarIntentResult.Launched
    } catch (t: Throwable) {
        CalendarIntentResult.Blocked(t.message ?: "Could not open calendar")
    }
}
