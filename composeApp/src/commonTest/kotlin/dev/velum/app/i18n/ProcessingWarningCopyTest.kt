package dev.velum.app.i18n

import dev.velum.core.model.ProcessingWarning
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProcessingWarningCopyTest {
    @Test
    fun truncatedAudioWarningIsLocalizedInBothLanguages() {
        val en = En.processingWarningText(ProcessingWarning.TRUNCATED_AUDIO)
        val pt = PtBr.processingWarningText(ProcessingWarning.TRUNCATED_AUDIO)
        assertTrue(!en.isNullOrBlank() && !pt.isNullOrBlank())
        assertNotEquals(en, pt)
    }

    @Test
    fun noneOrUnknownCodeShowsNothing() {
        assertNull(En.processingWarningText(null))
        assertNull(En.processingWarningText("something_else"))
    }
}
