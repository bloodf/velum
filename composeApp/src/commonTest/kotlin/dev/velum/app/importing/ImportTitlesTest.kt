package dev.velum.app.importing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImportTitlesTest {
    @Test
    fun stripsInternalPrefixExtensionAndUnderscores() {
        assertEquals("other-voice", ImportTitles.fromFileName("1793690166522_other-voice"))
        assertEquals("two-speakers", ImportTitles.fromFileName("1793688825965_two-speakers.wav"))
        assertEquals("Team standup notes", ImportTitles.fromFileName("share_1793688825965_Team_standup_notes.m4a"))
    }

    @Test
    fun keepsNamesThatOnlyLookLikeAPrefix() {
        assertEquals("2024 plan", ImportTitles.fromFileName("2024_plan.mp3"))
        assertEquals("note", ImportTitles.fromFileName("note.ogg"))
    }

    @Test
    fun blankLeftoverIsNull() {
        assertNull(ImportTitles.fromFileName("1793688825965_.wav"))
        assertNull(ImportTitles.fromFileName(""))
    }
}
