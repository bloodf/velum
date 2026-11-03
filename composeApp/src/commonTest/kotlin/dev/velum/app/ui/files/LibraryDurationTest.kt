package dev.velum.app.ui.files

import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryDurationTest {
    @Test fun shortClipsShowSecondsNotZeroMinutes() {
        assertEquals("36 s", libraryDurationLabel(36, "%d min", "%d s"))
        assertEquals("6 s", libraryDurationLabel(6, "%d min", "%d s"))
        assertEquals("0 s", libraryDurationLabel(0, "%d min", "%d s"))
    }

    @Test fun aMinuteOrMoreShowsWholeMinutes() {
        assertEquals("1 min", libraryDurationLabel(60, "%d min", "%d s"))
        assertEquals("1 min", libraryDurationLabel(119, "%d min", "%d s"))
        assertEquals("90 min", libraryDurationLabel(5400, "%d min", "%d s"))
    }
}
