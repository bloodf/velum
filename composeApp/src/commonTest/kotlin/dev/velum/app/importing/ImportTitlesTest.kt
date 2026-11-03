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

    @Test
    fun providerDisplayName_winsAndKeepsDateAndParentheses() {
        assertEquals("202501031200_call", ImportTitles.fromDisplayName("202501031200_call.wav"))
        assertEquals("Team standup (v2)", ImportTitles.fromDisplayName("Team standup (v2).wav"))
        ImportDisplayNames.put("/cache/1793688825965_Team_standup_v2.wav", "Team standup (v2).wav")
        assertEquals("Team standup (v2)", ImportTitles.forImport("/cache/1793688825965_Team_standup_v2.wav", "1793688825965_Team_standup_v2.wav"))
        // Taken once; later imports of the same path fall back to the cache name.
        assertEquals("Team standup v2", ImportTitles.forImport("/cache/1793688825965_Team_standup_v2.wav", "1793688825965_Team_standup_v2.wav"))
    }
}
