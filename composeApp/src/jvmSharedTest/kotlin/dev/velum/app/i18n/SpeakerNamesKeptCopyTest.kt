package dev.velum.app.i18n

import dev.velum.ai.pipeline.SPEAKER_NAMES_KEPT_MESSAGE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SpeakerNamesKeptCopyTest {
    @Test fun the_stored_failure_is_shown_in_the_app_language() {
        assertEquals(En.speakerNamesKept, processingFailureText(SPEAKER_NAMES_KEPT_MESSAGE, En))
        assertEquals(PtBr.speakerNamesKept, processingFailureText(SPEAKER_NAMES_KEPT_MESSAGE, PtBr))
        assertNotEquals(En.speakerNamesKept, PtBr.speakerNamesKept)
    }
}
