package dev.velum.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ForbiddenOpcodesTest {
    @Test
    fun knownDestructiveOpcodesAreForbidden() {
        listOf(5, 104, 0xFE20, 143).forEach { op ->
            assertTrue(ForbiddenOpcodes.isForbidden(op), "expected $op forbidden")
            assertFailsWith<IllegalArgumentException> {
                ForbiddenOpcodes.requireAllowed(op)
            }
        }
    }

    @Test
    fun safeOpcodesAllowed() {
        listOf(1, 9, 26, 28, 29, 0xFE10, 0xFE11, 0xFE12).forEach { op ->
            assertFalse(ForbiddenOpcodes.isForbidden(op))
            ForbiddenOpcodes.requireAllowed(op)
        }
    }

    @Test
    fun encodeCommand_rejectsForbidden() {
        assertFailsWith<IllegalArgumentException> {
            Framing.encodeCommand(ForbiddenOpcodes.CLEAR_ALL)
        }
        assertFailsWith<IllegalArgumentException> {
            Framing.encodeCommand(ForbiddenOpcodes.OTA)
        }
    }

    @Test
    fun encodeChunks_rejectsFe20() {
        assertFailsWith<IllegalArgumentException> {
            Unlock.encodeChunks(ForbiddenOpcodes.FE20, ByteArray(16) { 1 })
        }
    }

    @Test
    fun encodeBatteryRequest_isCmd9() {
        val frame = Battery.encodeBatteryRequest()
        // Empty params → trailing 0x00 of cmd u16 is trimmed (SDK-style).
        assertEquals(Framing.APP_PREFIX, frame[0])
        assertEquals(Commands.BATTERY_LIKELY, frame[1].toInt() and 0xFF)
    }
}
