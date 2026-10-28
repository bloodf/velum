package dev.velum.core.search

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.velum.core.db.VelumDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FtsSchemaReuseTest {
    @Test
    fun second_ensureSchema_does_not_recreate_template_fts() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        VelumDatabase.Schema.create(driver)
        val first = FtsIndex.ensureSchema(driver)
        assertTrue(first.available)
        // Missing table counts as needing (re)create so callers rebuild after seed.
        assertTrue(first.templateSchemaRecreated)

        FtsIndex.upsertTemplate(driver, "t1", "Meeting", "desc", "meeting", "prompt", "work")
        assertEquals(listOf("t1"), FtsIndex.searchTemplateIds(driver, "Meeting"))

        val second = FtsIndex.ensureSchema(driver)
        assertTrue(second.available)
        assertFalse(
            second.templateSchemaRecreated,
            "must not DROP template_fts on every launch once tags column exists",
        )
        assertEquals(
            listOf("t1"),
            FtsIndex.searchTemplateIds(driver, "Meeting"),
            "FTS rows must survive ensureSchema",
        )
    }
}
