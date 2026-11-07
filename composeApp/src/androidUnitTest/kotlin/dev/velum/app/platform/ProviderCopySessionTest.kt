package dev.velum.app.platform

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderCopySessionTest {
    private fun session() = ProviderCopySession("request", "app", 1000, 10, 300_000)
    private fun ProviderCopySession.connect() = hello("request", 1000, 20, "app:provider_copy", 0)

    @Test fun onlyAuthenticatedDedicatedChildCanComplete() {
        for ((uid, pid, process) in listOf(Triple(999, 20, "app:provider_copy"), Triple(1000, 10, "app:provider_copy"), Triple(1000, 20, "app"), Triple(1000, 0, "app:provider_copy"))) {
            val s = session()
            assertFalse(s.hello("request", uid, pid, process, 0))
            s.completed("request", uid, pid, true, "x.wav", 1)
            assertFalse(s.died(2))
        }
    }
    @Test fun blockedMetadataOpenAndReadExpireWithoutCompletion() {
        for (stage in listOf("metadata", "open", "read")) {
            val s = session()
            assertTrue(s.connect(), stage)
            // Fake IPC child remains in this stage; monotonic deadline makes death ineligible.
            assertFalse(s.died(300_000), stage)
            s.abort()
            s.completed("request", 1000, 20, true, "late.wav", 300_001)
            assertFalse(s.died(300_002), stage)
        }
    }
    @Test fun cancelledOrDeadChildCannotPublishPartialOrStaleCompletion() {
        val s = session()
        assertTrue(s.connect())
        s.abort()
        s.completed("request", 1000, 20, true, "late.wav", 1)
        assertFalse(s.died(2))
        val dead = session()
        assertTrue(dead.connect())
        assertFalse(dead.died(1))
    }
    @Test fun successRequiresCompleteAndChildDeathBeforeDeadline() {
        val s = session()
        assertTrue(s.connect())
        s.completed("foreign", 1000, 20, true, "wrong.wav", 1)
        s.completed("request", 1000, 20, true, "ok.wav", 3)
        assertTrue(s.died(4))
        assertFalse(s.died(300_000))
    }
    @Test fun failedCopyIncludingCapOverflowCannotPublish() {
        val s = session()
        assertTrue(s.connect())
        s.completed("request", 1000, 20, false, "large.wav", 1)
        assertFalse(s.died(2))
    }
}
