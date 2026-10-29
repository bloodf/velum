package dev.velum.core.data

import dev.velum.core.db.VelumDatabase
import kotlin.test.Test
import kotlin.test.assertEquals

class SchemaVersionTest {
    @Test
    fun schema_version_is_five_after_device_session_unique_index() {
        assertEquals(5L, VelumDatabase.Schema.version)
    }
}
