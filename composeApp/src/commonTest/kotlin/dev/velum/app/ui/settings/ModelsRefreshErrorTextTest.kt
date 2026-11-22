package dev.velum.app.ui.settings

import dev.velum.app.i18n.En
import dev.velum.app.i18n.PtBr
import dev.velum.app.ui.MISSING_API_KEY_STATUS
import kotlin.test.Test
import kotlin.test.assertEquals

class ModelsRefreshErrorTextTest {
    @Test fun a_missing_key_asks_for_the_key_instead_of_a_generic_failure() {
        for (strings in listOf(En, PtBr)) {
            assertEquals(strings.added.processingFailedApiKey, modelsRefreshErrorText(MISSING_API_KEY_STATUS, strings))
        }
    }

    @Test fun http_statuses_keep_their_messages() {
        assertEquals(En.settingsUi.modelsErrorUnauthorized, modelsRefreshErrorText(401, En))
        assertEquals(En.settingsUi.modelsErrorUnauthorized, modelsRefreshErrorText(403, En))
        assertEquals(En.settingsUi.modelsErrorNotFound, modelsRefreshErrorText(404, En))
        assertEquals(En.settingsUi.modelsErrorBadUrl, modelsRefreshErrorText(-1, En))
        assertEquals(PtBr.settingsUi.modelsErrorGeneric, modelsRefreshErrorText(500, PtBr))
        assertEquals(En.settingsUi.modelsErrorGeneric, modelsRefreshErrorText(null, En))
    }
}
