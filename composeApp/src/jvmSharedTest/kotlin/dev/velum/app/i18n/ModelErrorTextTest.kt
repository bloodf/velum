package dev.velum.app.i18n

import dev.velum.ai.local.LocalModelErrors
import dev.velum.app.ui.settings.modelErrorText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** #365: a model download error is short localized copy, with no URL or query string. */
class ModelErrorTextTest {
    @Test fun download_failed_is_localized_in_both_languages() {
        assertEquals(En.ux.modelDownloadFailed, modelErrorText(LocalModelErrors.DownloadFailed, En))
        assertEquals(PtBr.ux.modelDownloadFailed, modelErrorText(LocalModelErrors.DownloadFailed, PtBr))
        assertFalse(PtBr.ux.modelDownloadFailed == En.ux.modelDownloadFailed)
    }

    @Test fun an_error_saved_before_this_fix_loses_its_url() {
        val saved = "Connection reset while reading https://objects.example.test/m.bin?X-Amz-Signature=deadbeef&X-Amz-Expires=300 (retry)"
        val shown = modelErrorText(saved, En)
        assertFalse(shown.contains("https://") || shown.contains("Signature") || shown.contains("example.test"), shown)
    }

    @Test fun other_messages_pass_through() {
        assertEquals("Download failed: HTTP 403", modelErrorText("Download failed: HTTP 403", En))
    }
}
