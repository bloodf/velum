package dev.velum.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderCopyDeadlineTest {
    private class FakeCopyProcess(val stage: String) {
        var alive = true
        var stops = 0
        fun terminateOwnedSession() { stops++; alive = false }
    }

    @Test fun watchdogReclaimsBlockedMetadataOpenReadAndCloseWithoutWaitingForThem() {
        for (stage in listOf("metadata", "open", "read", "close")) {
            val process = FakeCopyProcess(stage)
            var now = 0L
            val deadline = ProviderCopyDeadline(300_000, { now }, process::terminateOwnedSession)
            now = 299_999
            deadline.check()
            assertTrue(process.alive, stage)
            now = 300_000
            deadline.check()
            assertFalse(process.alive, stage)
            deadline.check()
            assertEquals(1, process.stops, stage)
        }
    }

    @Test fun elapsedClockJumpAcrossSleepStillTerminatesOnNextTick() {
        var now = 1L
        var stops = 0
        val deadline = ProviderCopyDeadline(300_001, { now }) { stops++ }
        deadline.check()
        now = 900_000
        deadline.check()
        assertEquals(1, stops)
    }
}
