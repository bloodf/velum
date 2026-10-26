package dev.velum.ai.pipeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BoundedPcmRingBufferTest {
    @Test
    fun drops_oldest_when_full() {
        val ring = BoundedPcmRingBuffer(maxBytes = 8)
        ring.write(byteArrayOf(1, 2, 3, 4, 5, 6))
        assertEquals(6, ring.size)
        ring.write(byteArrayOf(7, 8, 9, 10))
        assertEquals(8, ring.size)
        val bytes = ring.toByteArray()
        assertEquals(8, bytes.size)
        // Newest bytes retained
        assertEquals(10, bytes.last().toInt())
    }

    @Test
    fun forMaxDuration_even_capacity() {
        val ring = BoundedPcmRingBuffer.forMaxDuration(16_000, 2)
        assertEquals(16_000 * 2 * 2, ring.capacity)
        assertTrue(ring.capacity % 2 == 0)
    }

    @Test
    fun copyRange_reads_logical_window() {
        val ring = BoundedPcmRingBuffer(maxBytes = 10)
        ring.write(byteArrayOf(1, 2, 3, 4, 5, 6))
        assertTrue(ring.copyRange(2, 6).contentEquals(byteArrayOf(3, 4, 5, 6)))
    }
}
