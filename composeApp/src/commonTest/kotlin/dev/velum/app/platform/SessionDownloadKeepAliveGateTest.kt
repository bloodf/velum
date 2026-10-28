package dev.velum.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionDownloadKeepAliveGateTest {
    @Test
    fun begin_startsPlatformOnce_end_stopsOnce() {
        var starts = 0
        var stops = 0
        val gate = SessionDownloadKeepAliveGate(
            startPlatform = { starts++ },
            stopPlatform = { stops++ },
        )

        assertFalse(gate.isActive)
        gate.onDownloadBegin()
        assertTrue(gate.isActive)
        assertEquals(1, starts)
        assertEquals(0, stops)

        gate.onDownloadEnd()
        assertFalse(gate.isActive)
        assertEquals(1, starts)
        assertEquals(1, stops)
    }

    @Test
    fun repeatedBegin_doesNotRestart_whileActive() {
        var starts = 0
        var stops = 0
        val gate = SessionDownloadKeepAliveGate(
            startPlatform = { starts++ },
            stopPlatform = { stops++ },
        )

        gate.onDownloadBegin()
        gate.onDownloadBegin()
        gate.onDownloadBegin()
        assertEquals(1, starts)
        assertTrue(gate.isActive)

        gate.onDownloadEnd()
        assertEquals(1, stops)
        assertFalse(gate.isActive)
    }

    @Test
    fun endWithoutBegin_isNoOp() {
        var starts = 0
        var stops = 0
        val gate = SessionDownloadKeepAliveGate(
            startPlatform = { starts++ },
            stopPlatform = { stops++ },
        )

        gate.onDownloadEnd()
        gate.onDownloadEnd()
        assertEquals(0, starts)
        assertEquals(0, stops)
        assertFalse(gate.isActive)
    }

    @Test
    fun successiveDownloads_startAndStopEachTime_noLeak() {
        var starts = 0
        var stops = 0
        val gate = SessionDownloadKeepAliveGate(
            startPlatform = { starts++ },
            stopPlatform = { stops++ },
        )

        repeat(3) {
            gate.onDownloadBegin()
            assertTrue(gate.isActive)
            // success / failure / cancel all end the same way
            gate.onDownloadEnd()
            assertFalse(gate.isActive)
        }

        assertEquals(3, starts)
        assertEquals(3, stops)
        assertFalse(gate.isActive)
    }

    @Test
    fun cancelPath_endAfterBegin_releasesKeepAlive() {
        var starts = 0
        var stops = 0
        val gate = SessionDownloadKeepAliveGate(
            startPlatform = { starts++ },
            stopPlatform = { stops++ },
        )

        gate.onDownloadBegin()
        // Mimic cancel: end keep-alive immediately, then job finally ends again.
        gate.onDownloadEnd()
        gate.onDownloadEnd()
        assertEquals(1, starts)
        assertEquals(1, stops)
        assertFalse(gate.isActive)
    }
}
