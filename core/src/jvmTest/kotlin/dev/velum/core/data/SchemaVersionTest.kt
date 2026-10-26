package dev.velum.core.data

import dev.velum.core.db.VelumDatabase
import kotlin.test.Test
import kotlin.test.assertEquals

class SchemaVersionTest {
    @Test
    fun schema_version_is_one_until_first_sqm() {
        assertEquals(1L, VelumDatabase.Schema.version)
    }
}
