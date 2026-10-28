package dev.velum.core.search

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.velum.core.data.DriverFactory
import dev.velum.core.data.VelumRepository
import dev.velum.core.data.createDatabase
import dev.velum.core.db.VelumDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FtsEmptySideRebuildTest {
    @Test
    fun emptiness_reports_sides_independently() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        VelumDatabase.Schema.create(driver)
        val ensured = FtsIndex.ensureSchema(driver)
        assertTrue(ensured.available)
        FtsIndex.upsertTemplate(driver, "t1", "Meeting", "d", "c", "p", "tag")
        val empty = FtsIndex.emptiness(driver)
        assertTrue(empty.recordingsEmpty)
        assertFalse(empty.templatesEmpty)
    }

    @Test
    fun coldStart_emptyLibrary_doesNotWipeTemplateFts() {
        // First open seeds + full rebuild.
        val handle = createDatabase(DriverFactory())
        val first = VelumRepository(handle)
        assertTrue(first.ftsAvailable)
        val templateHits = kotlinx.coroutines.runBlocking {
            first.searchTemplateIdsFts("Resumo")
        }
        assertTrue(templateHits.isNotEmpty(), "seed must index builtin templates")
        // Clear only recording_fts to simulate empty library with intact templates.
        FtsIndex.clearRecordings(handle.driver)
        val before = FtsIndex.emptiness(handle.driver)
        assertTrue(before.recordingsEmpty)
        assertFalse(before.templatesEmpty)

        // Selective rebuild of recordings only must leave template rows.
        first.rebuildFtsIndex(recordings = true, templates = false)
        val after = FtsIndex.emptiness(handle.driver)
        assertTrue(after.recordingsEmpty) // still no recordings in DB
        assertFalse(after.templatesEmpty)
        val stillThere = kotlinx.coroutines.runBlocking {
            first.searchTemplateIdsFts("Resumo")
        }
        assertEquals(templateHits, stillThere)
    }
}
