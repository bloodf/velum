package dev.velum.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** #113: only a real serial may key dedup; a missing serial is a consistent null. Synthetic values. */
class DedupDeviceSerialTest {
    @Test fun key_serial_wins_then_advertised_serial_then_null() {
        assertEquals("SYNTH-KEY", dedupDeviceSerial("SYNTH-KEY", "SYNTH-ADV"))
        assertEquals("SYNTH-ADV", dedupDeviceSerial(" ", "SYNTH-ADV"))
        assertNull(dedupDeviceSerial("", null))
        assertNull(dedupDeviceSerial("", "  "))
    }
}
