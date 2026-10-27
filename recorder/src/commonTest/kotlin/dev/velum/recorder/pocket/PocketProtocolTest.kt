package dev.velum.recorder.pocket

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PocketProtocolTest {

    @Test
    fun authenticate_andSkResponse() {
        assertEquals("APP&SK&ABCDEFGH12345678", PocketProtocol.authenticate("ABCDEFGH12345678"))
        assertTrue(PocketProtocol.parseSkResponse("MCU&SK&OK"))
        assertFalse(PocketProtocol.parseSkResponse("MCU&SK&ERR"))
    }

    @Test
    fun splitMessages_handlesBackToBack() {
        val parts = PocketProtocol.splitMessages("MCU&WIFIOMCU&OFF")
        assertEquals(listOf("MCU&WIFIO", "MCU&OFF"), parts)
    }

    @Test
    fun parseListRow_durationSeconds() {
        val row = assertNotNull(PocketProtocol.parseListRow("MCU&F&2026-03-28&20260328001919&25"))
        assertEquals("2026-03-28", row.date)
        assertEquals("20260328001919", row.timestamp)
        assertEquals(25, row.durationS)
        assertEquals(100_000, row.estimatedBytes)
    }

    @Test
    fun downloadCommand() {
        assertEquals(
            "APP&U&2026-03-28&20260328001919",
            PocketProtocol.download("2026-03-28", "20260328001919"),
        )
    }
}
