package dev.velum.app.importing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImportedTitleTest {
    @Test fun dropsExtensionAndTheCachePrefix() {
        assertEquals("other-voice", importedTitle("1793690166522_other-voice.wav"))
        assertEquals("two-speakers", importedTitle("1793688825965_two-speakers.wav"))
    }

    @Test fun keepsAReadableNameUntouched() {
        assertEquals("Team standup", importedTitle("Team standup.m4a"))
        assertEquals("2024_notes", importedTitle("2024_notes.mp3")) // a short year-like prefix is a real name
    }

    @Test fun nothingReadableGivesNull() {
        assertNull(importedTitle("1793690166522_.wav"))
        assertNull(importedTitle(".wav"))
    }
}
