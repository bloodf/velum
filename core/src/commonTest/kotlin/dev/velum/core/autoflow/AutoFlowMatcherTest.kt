package dev.velum.core.autoflow

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoFlowMatcherTest {
    private val noteProRule = AutoFlowMatcher.Rule(
        enabled = true,
        wakePhrase = null,
        durationMinSeconds = 0,
        durationMaxSeconds = 3 * 3600,
        sourceFilter = "note_pro",
        templateId = "velum.meeting",
    )

    @Test
    fun matchesNoteProWithinDuration() {
        assertTrue(
            AutoFlowMatcher.matches(
                noteProRule,
                AutoFlowMatcher.Candidate(source = "note_pro", durationSeconds = 120),
            ),
        )
    }

    @Test
    fun rejectsWrongSource() {
        assertFalse(
            AutoFlowMatcher.matches(
                noteProRule,
                AutoFlowMatcher.Candidate(source = "phone", durationSeconds = 120),
            ),
        )
    }

    @Test
    fun allFilterMatchesAnySource() {
        val rule = noteProRule.copy(sourceFilter = "all")
        assertTrue(
            AutoFlowMatcher.matches(
                rule,
                AutoFlowMatcher.Candidate(source = "import", durationSeconds = 10),
            ),
        )
    }

    @Test
    fun disabledNeverMatches() {
        assertFalse(
            AutoFlowMatcher.matches(
                noteProRule.copy(enabled = false),
                AutoFlowMatcher.Candidate(source = "note_pro", durationSeconds = 60),
            ),
        )
    }

    @Test
    fun wakePhraseRequiresHitInTitleOrTranscript() {
        val rule = noteProRule.copy(wakePhrase = "standup")
        assertFalse(
            AutoFlowMatcher.matches(
                rule,
                AutoFlowMatcher.Candidate(source = "note_pro", durationSeconds = 60, title = "Daily"),
            ),
        )
        assertTrue(
            AutoFlowMatcher.matches(
                rule,
                AutoFlowMatcher.Candidate(
                    source = "note_pro",
                    durationSeconds = 60,
                    title = "Morning standup",
                ),
            ),
        )
    }

    @Test
    fun durationBoundsExclusiveOutside() {
        assertFalse(
            AutoFlowMatcher.matches(
                noteProRule.copy(durationMinSeconds = 60, durationMaxSeconds = 120),
                AutoFlowMatcher.Candidate(source = "note_pro", durationSeconds = 30),
            ),
        )
        assertFalse(
            AutoFlowMatcher.matches(
                noteProRule.copy(durationMinSeconds = 60, durationMaxSeconds = 120),
                AutoFlowMatcher.Candidate(source = "note_pro", durationSeconds = 200),
            ),
        )
    }
}
