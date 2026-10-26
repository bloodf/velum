package dev.velum.core.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FtsQueryTest {
    @Test
    fun blankYieldsNull() {
        assertNull(FtsQuery.fromUserInput("  "))
    }

    @Test
    fun stripsOperatorsAndAddsPrefix() {
        assertEquals("sprint* planning*", FtsQuery.fromUserInput("sprint planning"))
        assertEquals("BLE*", FtsQuery.fromUserInput("\"BLE*\""))
    }
}

class RecordingSearchTest {
    @Test
    fun fallsBackWhenFtsEmpty() {
        val items = listOf(
            dev.velum.core.db.Recording(
                id = "1",
                session_id = null,
                folder_id = null,
                title = "Sprint planning",
                source = "phone",
                device_serial = null,
                started_at = 1,
                duration_seconds = 1,
                size_bytes = 1,
                local_path = null,
                transcript = "BLE sync",
                summary_markdown = null,
                status = "ready",
                trashed = 0,
                created_at = 1,
            ),
        )
        val result = RecordingSearch.recordings(items, "BLE", emptyList())
        assertEquals(1, result.size)
        assertEquals("1", result[0].id)
    }

    @Test
    fun prefersFtsOrder() {
        val a = recording("a", "Alpha")
        val b = recording("b", "Beta BLE")
        val result = RecordingSearch.recordings(listOf(a, b), "BLE", listOf("b", "a"))
        assertEquals(listOf("b", "a"), result.map { it.id })
    }

    private fun recording(id: String, title: String) = dev.velum.core.db.Recording(
        id = id,
        session_id = null,
        folder_id = null,
        title = title,
        source = "phone",
        device_serial = null,
        started_at = 1,
        duration_seconds = 1,
        size_bytes = 1,
        local_path = null,
        transcript = null,
        summary_markdown = null,
        status = "ready",
        trashed = 0,
        created_at = 1,
    )
}
