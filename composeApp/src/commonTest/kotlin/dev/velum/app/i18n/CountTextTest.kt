package dev.velum.app.i18n

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CountTextTest {
    @Test fun one_and_many_in_english() {
        assertEquals("1 recording · 4 MB", countText(1, En.settingsUi.storageUsageFormatOne, En.settingsUi.storageUsageFormat).replace("%s", "4 MB"))
        assertEquals("3 recordings · 4 MB", countText(3, En.settingsUi.storageUsageFormatOne, En.settingsUi.storageUsageFormat).replace("%s", "4 MB"))
        assertEquals("1 result", countText(1, En.searchResultsCountOne, En.searchResultsCount))
        assertEquals("2 results", countText(2, En.searchResultsCountOne, En.searchResultsCount))
        assertEquals("1 selected", countText(1, En.ux.selectionCountOne, En.ux.selectionCount))
        assertEquals("Imported 1 template", countText(1, En.templatesImportOkFormatOne, En.templatesImportOkFormat))
        assertEquals("1 model loaded", countText(1, En.settingsUi.modelsLoadedFormatOne, En.settingsUi.modelsLoadedFormat))
    }

    @Test fun one_and_many_in_portuguese() {
        assertEquals("1 gravação · 4 MB", countText(1, PtBr.settingsUi.storageUsageFormatOne, PtBr.settingsUi.storageUsageFormat).replace("%s", "4 MB"))
        assertEquals("3 gravações · 4 MB", countText(3, PtBr.settingsUi.storageUsageFormatOne, PtBr.settingsUi.storageUsageFormat).replace("%s", "4 MB"))
        assertEquals("1 resultado", countText(1, PtBr.searchResultsCountOne, PtBr.searchResultsCount))
        assertEquals("2 resultados", countText(2, PtBr.searchResultsCountOne, PtBr.searchResultsCount))
        assertEquals("1 selecionada", countText(1, PtBr.ux.selectionCountOne, PtBr.ux.selectionCount))
        assertEquals("Importado 1 modelo", countText(1, PtBr.templatesImportOkFormatOne, PtBr.templatesImportOkFormat))
        assertEquals("1 modelo carregado", countText(1, PtBr.settingsUi.modelsLoadedFormatOne, PtBr.settingsUi.modelsLoadedFormat))
    }

    @Test fun zero_uses_the_plural_form() = assertEquals("0 results", countText(0, En.searchResultsCountOne, En.searchResultsCount))

    @Test fun no_plural_workaround_is_left_in_the_count_strings() {
        for (s in listOf(
            PtBr.searchResultsCount, En.searchResultsCount, PtBr.ux.selectionCount, En.ux.selectionCount,
            PtBr.templatesImportOkFormat, En.templatesImportOkFormat,
        )) assertTrue("(s)" !in s, s)
    }
}
