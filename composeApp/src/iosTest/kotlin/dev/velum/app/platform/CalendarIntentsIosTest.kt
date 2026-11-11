@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.velum.app.platform

import platform.EventKitUI.EKEventEditViewActionCanceled
import platform.EventKitUI.EKEventEditViewController
import platform.Foundation.NSDate
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.timeZoneWithName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CalendarIntentsIosTest {
    @Test
    fun mapsFieldsAndAbsoluteDatesWithoutReadingCalendars() {
        val start = NSDate.dateWithTimeIntervalSince1970(1700000000.0)
        val zone = NSTimeZone.timeZoneWithName("America/Sao_Paulo")!!
        val session = CalendarEditorSession("Review 📅", "First line\nSecond line", start, zone)
        val event = session.controller.event!!
        assertEquals("Review 📅", event.title)
        assertEquals("First line\nSecond line", event.notes)
        assertEquals(1700000000.0, event.startDate!!.timeIntervalSince1970)
        assertEquals(1700003600.0, event.endDate!!.timeIntervalSince1970)
        assertEquals(zone.name, event.timeZone!!.name)
        assertNull(event.calendar)
        assertSame(session, session.controller.editViewDelegate)
    }

    @Test
    fun cancelDismissesOnceAndReleasesDelegateAfterCompletion() {
        var dismissals = 0
        var completion: (() -> Unit)? = null
        val session = CalendarEditorSession("", "", NSDate(), NSTimeZone.timeZoneWithName("UTC")!!) { _, done ->
            dismissals++
            completion = done
        }
        session.eventEditViewController(EKEventEditViewController(), EKEventEditViewActionCanceled)
        assertEquals(0, dismissals)
        session.eventEditViewController(session.controller, EKEventEditViewActionCanceled)
        session.eventEditViewController(session.controller, EKEventEditViewActionCanceled)
        assertEquals(1, dismissals)
        assertSame(session, session.controller.editViewDelegate)
        completion!!()
        assertNull(session.controller.editViewDelegate)
    }

    @Test
    fun retainsActiveSessionUntilCancelDismissalCompletes() {
        var completion: (() -> Unit)? = null
        assertEquals(CalendarIntentResult.Launched, presentCalendarEditor(
            "title", "notes", dismiss = { _, done -> completion = done },
        ) {})
        val session = activeCalendarEditor!!
        assertSame(session, session.controller.editViewDelegate)
        assertTrue(presentCalendarEditor("other", "") {} is CalendarIntentResult.Blocked)
        session.eventEditViewController(session.controller, EKEventEditViewActionCanceled)
        assertSame(session, activeCalendarEditor)
        completion!!()
        assertNull(activeCalendarEditor)
        assertNull(session.controller.editViewDelegate)
    }

    @Test
    fun presentationFailureReleasesSessionAndReturnsBlocked() {
        val result = presentCalendarEditor("title", "notes") { error("Presentation failed") }
        assertTrue(result is CalendarIntentResult.Blocked)
        assertNull(activeCalendarEditor)
    }
}
