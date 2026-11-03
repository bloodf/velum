package dev.velum.core.calendar

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CalendarEventSummaryTest {
    private val s = 1_792_000_000_000L
    private val min = 60_000L

    @Test fun eventEndingAtTheStartIsOver() =
        assertFalse(CalendarEventSummary("a", s - 60 * min, s).isOnAt(s))

    @Test fun eventBeginningAtTheStartIsOn() =
        assertTrue(CalendarEventSummary("b", s, s + 30 * min).isOnAt(s))

    @Test fun backToBackMeetingsDoNotShareTheBoundary() {
        val first = CalendarEventSummary("first", s - 30 * min, s)
        val second = CalendarEventSummary("second", s, s + 30 * min)
        assertFalse(first.isOnAt(s))
        assertTrue(second.isOnAt(s))
    }

    @Test fun meetingHoursLaterIsNotOn() =
        assertFalse(CalendarEventSummary("c", s + 5 * 60 * min, s + 6 * 60 * min).isOnAt(s))

    @Test fun graceCoversAMeetingAboutToBegin() {
        assertTrue(CalendarEventSummary("d", s + 4 * min, s + 40 * min).isOnAt(s))
        assertFalse(CalendarEventSummary("e", s + 6 * min, s + 40 * min).isOnAt(s))
    }
}
