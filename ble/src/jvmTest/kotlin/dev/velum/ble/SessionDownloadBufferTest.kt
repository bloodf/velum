package dev.velum.ble

import dev.velum.ble.client.SessionDownloadBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SessionDownloadBufferTest {
    @Test
    fun concatenates_chunks() {
        val buf = SessionDownloadBuffer(maxBytes = 1024)
        buf.write(byteArrayOf(1, 2, 3))
        buf.write(byteArrayOf(4, 5))
        assertEquals(5, buf.size())
        assertTrue(buf.toByteArray().contentEquals(byteArrayOf(1, 2, 3, 4, 5)))
    }

    @Test
    fun rejects_over_cap() {
        val buf = SessionDownloadBuffer(maxBytes = 4)
        buf.write(byteArrayOf(1, 2, 3))
        assertFailsWith<IllegalArgumentException> {
            buf.write(byteArrayOf(4, 5))
        }
    }
}
