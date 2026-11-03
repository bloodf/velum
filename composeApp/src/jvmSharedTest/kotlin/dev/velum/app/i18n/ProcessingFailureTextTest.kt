package dev.velum.app.i18n

import dev.velum.ai.ApiErrorSanitizer
import dev.velum.ai.pipeline.INSUFFICIENT_STORAGE_MESSAGE
import kotlin.test.Test
import kotlin.test.assertEquals

/** #348: a stored processing failure shows in the app language, for the failures the processor stores itself. */
class ProcessingFailureTextTest {
    @Test fun out_of_storage_failure_is_shown_in_the_app_language() {
        // The processor stores the sanitized message; the screen must still recognise it after that round trip.
        val stored = ApiErrorSanitizer.sanitize(INSUFFICIENT_STORAGE_MESSAGE)
        assertEquals(En.ux.insufficientStorage, processingFailureText(stored, En))
        assertEquals(PtBr.ux.insufficientStorage, processingFailureText(stored, PtBr))
        assertEquals(false, processingFailureText(stored, PtBr) == INSUFFICIENT_STORAGE_MESSAGE)
    }

    @Test fun english_copy_matches_the_stored_text_and_portuguese_is_not_english() {
        assertEquals(INSUFFICIENT_STORAGE_MESSAGE, En.ux.insufficientStorage)
        assertEquals(false, PtBr.ux.insufficientStorage.contains("storage"))
    }

    @Test fun unknown_and_missing_failures_pass_through_unchanged() {
        assertEquals(null, processingFailureText(null, PtBr))
        assertEquals("Provider said no (HTTP 500)", processingFailureText("Provider said no (HTTP 500)", PtBr))
    }
}
