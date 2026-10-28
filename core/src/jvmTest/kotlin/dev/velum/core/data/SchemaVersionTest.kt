package dev.velum.core.data

import dev.velum.core.db.VelumDatabase
import kotlin.test.Test
import kotlin.test.assertEquals

class SchemaVersionTest {
    @Test
    fun schema_version_is_four_after_summary_version_migration() {
        assertEquals(4L, VelumDatabase.Schema.version)
    }
}
