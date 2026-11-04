package dev.velum.app.ui.importing

import dev.velum.app.i18n.En
import dev.velum.app.i18n.PtBr
import kotlin.test.Test
import kotlin.test.assertEquals

class SyncSummaryTest {
    @Test
    fun singularAndPluralPerCount_inBothLocales() {
        assertEquals("Done · 1 new · 1 dupe · 1 failed · 1 skipped", syncSummary(En, 1, 1, 1, 1))
        assertEquals("Done · 0 new · 2 dupes · 5 failed · 3 skipped", syncSummary(En, 0, 2, 5, 3))
        assertEquals("Pronto · 1 novo · 1 duplicado · 1 falha · 1 ignorado", syncSummary(PtBr, 1, 1, 1, 1))
        assertEquals("Pronto · 3 novos · 0 duplicados · 2 falhas · 4 ignorados", syncSummary(PtBr, 3, 0, 2, 4))
    }
}
