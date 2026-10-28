package dev.velum.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BatteryTest {
    @Test
    fun parse_noteProExample93Percent() {
        val reading = BatteryReading.parse(byteArrayOf(0x00, 0x5d))
        assertEquals(93, reading.percent)
        assertEquals(93, Battery.parseOrNull(byteArrayOf(0x00, 0x5d))!!.percent)
    }

    @Test
    fun parse_rejectsTruncatedOneByteAndEmpty() {
        assertFailsWith<IllegalArgumentException> {
            BatteryReading.parse(ByteArray(0))
        }
        assertFailsWith<IllegalArgumentException> {
            BatteryReading.parse(byteArrayOf(0x64)) // 1-byte truncated (need charging+level)
        }
        assertNull(Battery.parseOrNull(ByteArray(0)))
        assertNull(Battery.parseOrNull(byteArrayOf(0x64)))
        assertNull(BatteryReading.parseOrNull(byteArrayOf(0x00)))
    }

    @Test
    fun parseOrNull_outOfRangeFfWhileCharging_returnsNull() {
        // Hardware-observed: charging flag set, level 0xFF — must not throw on app path.
        val chargingUnknown = byteArrayOf(0x01, 0xFF.toByte())
        assertFailsWith<IllegalArgumentException> {
            BatteryReading.parse(chargingUnknown)
        }
        assertNull(Battery.parseOrNull(chargingUnknown))
        assertNull(Battery.parseOrNull(byteArrayOf(0x00, 0xFF.toByte())))
        assertNull(Battery.parseOrNull(byteArrayOf(0x00, 0x65))) // 101
        assertEquals(100, BatteryReading.parse(byteArrayOf(0x00, 0x64)).percent)
        assertEquals(0, Battery.parseOrNull(byteArrayOf(0x01, 0x00))!!.percent)
    }

    @Test
    fun toString_omitsRawBytes() {
        val reading = BatteryReading.parse(byteArrayOf(0x00, 50))
        val text = reading.toString()
        assertTrue("50" in text)
        assertTrue("bytes" in text)
        assertFalse(text.contains(byteArrayOf(0x00, 50).contentToString()))
    }
}
