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

    @Test fun every_code_maps_to_its_own_field_in_both_languages() {
        for ((strings, h403) in listOf(En to "403", PtBr to "403")) {
            val ux = strings.ux
            assertEquals(ux.modelStorageFull, modelErrorText(LocalModelErrors.StorageFull, strings))
            assertEquals(ux.modelDownloadFailed, modelErrorText(LocalModelErrors.DownloadFailed, strings))
            assertEquals(ux.modelDownloadError, modelErrorText(LocalModelErrors.DownloadError, strings))
            assertEquals(ux.modelChecksumMismatch, modelErrorText(LocalModelErrors.ChecksumMismatch, strings))
            assertEquals(ux.modelIncomplete, modelErrorText(LocalModelErrors.Incomplete, strings))
            assertEquals(ux.modelNoDownloadUrl, modelErrorText(LocalModelErrors.NoDownloadUrl, strings))
            assertEquals(ux.modelChecksumNotPinned, modelErrorText(LocalModelErrors.ChecksumNotPinned, strings))
            assertEquals(ux.modelHttpsRequired, modelErrorText(LocalModelErrors.HttpsRequired, strings))
            assertEquals(ux.modelHttpErrorFormat.replace("%d", h403), modelErrorText(LocalModelErrors.http(403), strings))
        }
    }

    @Test fun the_copy_differs_between_languages_and_never_names_a_missing_button() {
        val fields: (Strings) -> List<String> = { s ->
            val u = s.ux
            listOf(u.modelStorageFull, u.modelDownloadFailed, u.modelDownloadError, u.modelChecksumMismatch, u.modelIncomplete,
                u.modelNoDownloadUrl, u.modelChecksumNotPinned, u.modelHttpsRequired, u.modelHttpErrorFormat)
        }
        fields(En).zip(fields(PtBr)).forEach { (en, pt) -> assertFalse(en == pt, en) }
        assertFalse(En.ux.modelIncomplete.contains("Delete") || PtBr.ux.modelIncomplete.contains("Exclua"))
    }

    @Test fun a_mixed_case_url_in_an_old_saved_error_is_removed() {
        val shown = modelErrorText("Failed at HTTPS://Host.Example/m.bin?X-Amz-Signature=1 now", En)
        assertFalse(shown.contains("Host") || shown.contains("Signature"), shown)
    }
}
