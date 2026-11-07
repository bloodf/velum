package dev.velum.app

import dev.velum.app.i18n.En
import dev.velum.app.i18n.PtBr
import dev.velum.app.i18n.countText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CountTextTest {
    @Test
    fun singularAndPlural_inBothLocales() {
        assertEquals("1 result", countText(1, En.searchResultsCountOne, En.searchResultsCount))
        assertEquals("0 results", countText(0, En.searchResultsCountOne, En.searchResultsCount))
        assertEquals("5 results", countText(5, En.searchResultsCountOne, En.searchResultsCount))
        assertEquals("1 resultado", countText(1, PtBr.searchResultsCountOne, PtBr.searchResultsCount))
        assertEquals("2 resultados", countText(2, PtBr.searchResultsCountOne, PtBr.searchResultsCount))
        assertEquals("Imported 1 template", countText(1, En.templatesImportOkFormatOne, En.templatesImportOkFormat))
        assertEquals("Imported 3 templates", countText(3, En.templatesImportOkFormatOne, En.templatesImportOkFormat))
        assertEquals("Importado 1 modelo", countText(1, PtBr.templatesImportOkFormatOne, PtBr.templatesImportOkFormat))
        assertEquals("Importados 2 modelos", countText(2, PtBr.templatesImportOkFormatOne, PtBr.templatesImportOkFormat))
        assertEquals("1 selected", countText(1, En.ux.selectionCountOne, En.ux.selectionCount))
        assertEquals("4 selected", countText(4, En.ux.selectionCountOne, En.ux.selectionCount))
        assertEquals("1 selecionada", countText(1, PtBr.ux.selectionCountOne, PtBr.ux.selectionCount))
        assertEquals("3 selecionadas", countText(3, PtBr.ux.selectionCountOne, PtBr.ux.selectionCount))
    }

    @Test
    fun noLocaleKeepsTheParenthesisPlural() {
        for (s in listOf(En, PtBr)) for (text in listOf(
            s.searchResultsCountOne, s.searchResultsCount, s.templatesImportOkFormatOne, s.templatesImportOkFormat,
            s.ux.selectionCountOne, s.ux.selectionCount,
        )) assertFalse(text.contains("(s)"), text)
    }
}
