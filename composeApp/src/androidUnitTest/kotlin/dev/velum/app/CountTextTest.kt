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
        assertEquals("1 result", countText(1, En.searchResultsOne, En.searchResultsCount))
        assertEquals("0 results", countText(0, En.searchResultsOne, En.searchResultsCount))
        assertEquals("5 results", countText(5, En.searchResultsOne, En.searchResultsCount))
        assertEquals("1 resultado", countText(1, PtBr.searchResultsOne, PtBr.searchResultsCount))
        assertEquals("2 resultados", countText(2, PtBr.searchResultsOne, PtBr.searchResultsCount))
        assertEquals("Imported 1 template", countText(1, En.templatesImportOkOne, En.templatesImportOkFormat))
        assertEquals("Imported 3 templates", countText(3, En.templatesImportOkOne, En.templatesImportOkFormat))
        assertEquals("Importado 1 modelo", countText(1, PtBr.templatesImportOkOne, PtBr.templatesImportOkFormat))
        assertEquals("Importados 2 modelos", countText(2, PtBr.templatesImportOkOne, PtBr.templatesImportOkFormat))
        assertEquals("1 selected", countText(1, En.ux.selectionOne, En.ux.selectionCount))
        assertEquals("4 selected", countText(4, En.ux.selectionOne, En.ux.selectionCount))
        assertEquals("1 selecionada", countText(1, PtBr.ux.selectionOne, PtBr.ux.selectionCount))
        assertEquals("3 selecionadas", countText(3, PtBr.ux.selectionOne, PtBr.ux.selectionCount))
    }

    @Test
    fun noLocaleKeepsTheParenthesisPlural() {
        for (s in listOf(En, PtBr)) for (text in listOf(
            s.searchResultsOne, s.searchResultsCount, s.templatesImportOkOne, s.templatesImportOkFormat,
            s.ux.selectionOne, s.ux.selectionCount,
        )) assertFalse(text.contains("(s)"), text)
    }
}
