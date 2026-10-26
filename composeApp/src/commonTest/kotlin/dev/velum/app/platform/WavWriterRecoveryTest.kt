package dev.velum.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WavWriterRecoveryTest {
    @Test
    fun recoveredDataSize_stripsHeader() {
        assertEquals(0, WavWriter.recoveredDataSize(44))
        assertEquals(100, WavWriter.recoveredDataSize(144))
        assertEquals(0, WavWriter.recoveredDataSize(10))
    }

    @Test
    fun needsHeaderRewrite_whenZeroOrStale() {
        assertTrue(WavWriter.needsHeaderRewrite(fileLength = 144, headerDataSize = 0))
        assertTrue(WavWriter.needsHeaderRewrite(fileLength = 144, headerDataSize = 50))
        assertFalse(WavWriter.needsHeaderRewrite(fileLength = 144, headerDataSize = 100))
        assertFalse(WavWriter.needsHeaderRewrite(fileLength = 20, headerDataSize = 0))
    }

    @Test
    fun durationSeconds_fromPcm16Mono() {
        // 16 kHz * 2 bytes * 2 seconds = 64_000
        assertEquals(2, WavWriter.durationSeconds(64_000, 16_000))
        assertEquals(0, WavWriter.durationSeconds(0, 16_000))
    }
}
