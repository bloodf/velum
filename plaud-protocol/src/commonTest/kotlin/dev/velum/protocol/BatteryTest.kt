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
    }

    @Test
    fun parse_rejectsEmptyAndOutOfRange() {
        assertFailsWith<IllegalArgumentException> {
            BatteryReading.parse(ByteArray(0))
        }
        assertFailsWith<IllegalArgumentException> {
            BatteryReading.parse(byteArrayOf(0x00, 0xFF.toByte()))
        }
        assertNull(BatteryReading.parseOrNull(ByteArray(0)))
        assertNull(BatteryReading.parseOrNull(byteArrayOf(0x65))) // 101
        assertEquals(100, BatteryReading.parse(byteArrayOf(0x64)).percent)
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
