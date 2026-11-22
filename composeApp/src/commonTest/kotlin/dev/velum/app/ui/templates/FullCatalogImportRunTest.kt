package dev.velum.app.ui.templates

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class FullCatalogImportRunTest {
    @Test fun leaving_the_tab_mid_import_is_not_reported_as_a_failure() = runTest {
        val running = mutableListOf<Boolean>()
        var failed = 0
        val never = CompletableDeferred<Unit>()
        val job = launch { runFullCatalogImport({ never.await() }, { running += it }, { failed++ }) }
        runCurrent()
        job.cancel()
        job.join()
        assertEquals(0, failed)
        assertEquals(listOf(true, false), running)
    }

    @Test fun a_real_failure_is_reported_and_clears_the_running_flag() = runTest {
        val running = mutableListOf<Boolean>()
        var failed = 0
        runFullCatalogImport({ error("disk") }, { running += it }, { failed++ })
        assertEquals(1, failed)
        assertEquals(listOf(true, false), running)
    }

    @Test fun success_reports_nothing() = runTest {
        var failed = 0
        runFullCatalogImport({ }, { }, { failed++ })
        assertEquals(0, failed)
    }
}
