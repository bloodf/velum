package dev.velum.app.search

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.velum.core.data.DriverFactory
import dev.velum.core.data.VelumRepository
import dev.velum.core.data.createDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The production DriverFactory must give Android a SQLite with FTS5 (framework SQLite has only
 * FTS3/4), so recording and template search use the index instead of a full scan. Own DB file.
 * Debug build only: the minifiedTest APK has no R8 keeps for the app/core symbols used here.
 */
@RunWith(AndroidJUnit4::class)
class AndroidFtsDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After fun cleanup() { context.deleteDatabase(DB_NAME) }

    @Test fun production_driver_has_fts5_and_searches_templates_and_recordings() = runBlocking {
        context.deleteDatabase(DB_NAME)
        val handle = createDatabase(DriverFactory(context, DB_NAME))
        try {
            val repo = VelumRepository(handle)
            assertTrue("FTS must be available on Android (failure=${repo.ftsFailure})", repo.ftsAvailable)
            repo.upsertTemplate("tpl-fts5-probe", "Reunião semanal", "meeting", "", "Synthetic prompt")
            assertEquals(listOf("tpl-fts5-probe"), repo.searchTemplateIdsFts("reuniao*").filter { it == "tpl-fts5-probe" })
            repo.insertRecording(
                id = "rec-fts5-probe", sessionId = null, folderId = null, title = "Planejamento", source = "phone",
                deviceSerial = null, startedAt = 1, durationSeconds = 0, sizeBytes = 0, localPath = null,
                transcript = "Synthetic orçamento discussion", summaryMarkdown = null, status = "ready",
            )
            assertEquals(listOf("rec-fts5-probe"), repo.searchRecordingIdsFts("orcamento*"))
        } finally {
            handle.driver.close()
        }
    }

    private companion object { const val DB_NAME = "fts5-probe-instrumented.db" }
}
