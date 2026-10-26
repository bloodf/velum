package dev.velum.core.calendar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private var appContext: Context? = null

fun installCalendarContext(context: Context) {
    appContext = context.applicationContext
}

actual fun createCalendarEventProvider(): CalendarEventProvider =
    AndroidCalendarEventProvider { appContext }

class AndroidCalendarEventProvider(
    private val contextProvider: () -> Context?,
) : CalendarEventProvider {
    override suspend fun currentOrNextEvent(aroundEpochMs: Long): CalendarLookupResult =
        withContext(Dispatchers.IO) {
            val context = contextProvider()
                ?: return@withContext CalendarLookupResult.Unavailable("No Android context")
            val granted = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) return@withContext CalendarLookupResult.PermissionDenied

            val windowStart = aroundEpochMs - 15 * 60_000L
            val windowEnd = aroundEpochMs + 24 * 60 * 60_000L
            val projection = arrayOf(
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
            )
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
                .appendPath(windowStart.toString())
                .appendPath(windowEnd.toString())
                .build()
            context.contentResolver.query(
                uri,
                projection,
                null,
                null,
                "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { cursor ->
                val titleIdx = cursor.getColumnIndex(CalendarContract.Instances.TITLE)
                val beginIdx = cursor.getColumnIndex(CalendarContract.Instances.BEGIN)
                val endIdx = cursor.getColumnIndex(CalendarContract.Instances.END)
                if (titleIdx < 0 || beginIdx < 0 || endIdx < 0) {
                    return@withContext CalendarLookupResult.Ok(null)
                }
                var best: CalendarEventSummary? = null
                while (cursor.moveToNext()) {
                    val title = cursor.getString(titleIdx)?.trim().orEmpty()
                    if (title.isEmpty()) continue
                    val begin = cursor.getLong(beginIdx)
                    val end = cursor.getLong(endIdx)
                    val summary = CalendarEventSummary(title, begin, end)
                    if (begin <= aroundEpochMs && aroundEpochMs <= end) {
                        return@withContext CalendarLookupResult.Ok(summary)
                    }
                    if (begin >= aroundEpochMs) {
                        best = summary
                        break
                    }
                }
                CalendarLookupResult.Ok(best)
            } ?: CalendarLookupResult.Ok(null)
        }
}
