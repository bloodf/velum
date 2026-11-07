package dev.velum.app.platform

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class ExternalShareConsentTest {
    @Test fun processing_waits_for_explicit_approval_and_runs_only_once() = runTest {
        val consent = ExternalShareConsent()
        var imports = 0
        val pending = async { consent.runIfApproved { imports++ } }
        runCurrent()
        assertFalse(pending.isCompleted)
        assertEquals(0, imports)
        consent.decide(true)
        assertTrue(pending.await())
        assertFalse(consent.runIfApproved { imports++ })
        assertEquals(1, imports)
    }

    @Test fun dismissal_cannot_be_changed_to_approval() = runTest {
        val consent = ExternalShareConsent()
        consent.decide(false)
        consent.decide(true)
        var imports = 0
        assertFalse(consent.runIfApproved { imports++ })
        assertEquals(0, imports)
    }

    @Test fun cancelled_wait_preserves_the_pending_decision() = runTest {
        val consent = ExternalShareConsent()
        var imports = 0
        val stopped = async { consent.runIfApproved { imports++ } }
        runCurrent()
        stopped.cancel()
        stopped.join()
        val resumed = async { consent.runIfApproved { imports++ } }
        runCurrent()
        assertFalse(resumed.isCompleted)
        consent.decide(true)
        assertTrue(resumed.await())
        assertEquals(1, imports)
    }

    @Test fun recovery_never_inherits_another_shares_approval() = runTest {
        val previous = ExternalShareConsent()
        previous.decide(true)
        assertTrue(previous.runIfApproved {})
        val recovered = ExternalShareConsent()
        var imports = 0
        val pending = async { recovered.runIfApproved { imports++ } }
        runCurrent()
        assertFalse(pending.isCompleted)
        assertEquals(0, imports)
        recovered.decide(false)
        assertFalse(pending.await())
    }
}
