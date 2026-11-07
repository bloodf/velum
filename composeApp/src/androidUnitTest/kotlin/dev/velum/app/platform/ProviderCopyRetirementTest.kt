package dev.velum.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProviderCopyRetirementTest {
    @Test fun cancelledCallerReturnsWhileNextBindWaitsForDelayedChildDeath() = runTest {
        val retirement = ProviderCopyRetirement()
        val lock = Mutex()
        lateinit var childDeath: CompletableDeferred<Unit>
        var stops = 0
        var nextBinds = 0
        val caller = launch {
            lock.withLock {
                retirement.awaitPrevious()
                childDeath = retirement.register()
                try { awaitCancellation() } finally { stops++ } // STOP delivery is deliberately delayed.
            }
        }
        runCurrent()
        val next = launch {
            lock.withLock {
                retirement.awaitPrevious()
                nextBinds++
            }
        }
        caller.cancel()
        runCurrent()
        assertTrue(caller.isCancelled)
        assertTrue(caller.isCompleted) // No join on Binder death in caller cancellation cleanup.
        assertEquals(1, stops)
        assertEquals(0, nextBinds)
        assertFalse(next.isCompleted)
        childDeath.complete(Unit) // Actual death, not STOP dispatch, releases admission.
        runCurrent()
        assertEquals(1, nextBinds)
        assertTrue(next.isCompleted)
    }

    @Test fun timedOutRequestDoesNotLetQueuedRequestBindRetiringEndpoint() = runTest {
        val retirement = ProviderCopyRetirement()
        val oldDeath = retirement.register()
        val timeout = CompletableDeferred<Unit>()
        var returned = false
        val caller = launch { timeout.await(); returned = true }
        var bound = false
        val next = launch { retirement.awaitPrevious(); bound = true }
        timeout.complete(Unit)
        runCurrent()
        assertTrue(returned)
        assertTrue(caller.isCompleted)
        assertFalse(bound)
        oldDeath.complete(Unit)
        runCurrent()
        assertTrue(bound)
        assertTrue(next.isCompleted)
    }

    @Test fun staleDeathCallbackCannotRetireNewRequest() = runTest {
        val retirement = ProviderCopyRetirement()
        val firstDeath = retirement.register()
        firstDeath.complete(Unit)
        retirement.awaitPrevious()
        val secondDeath = retirement.register()
        var thirdBinds = 0
        val third = launch { retirement.awaitPrevious(); thirdBinds++ }
        runCurrent()
        firstDeath.complete(Unit) // Duplicate old Binder/connection callback after a new session exists.
        runCurrent()
        assertEquals(0, thirdBinds)
        assertFalse(third.isCompleted)
        secondDeath.complete(Unit)
        runCurrent()
        assertEquals(1, thirdBinds)
        assertTrue(third.isCompleted)
    }

    @Test fun queuedCancellationDoesNotCancelDetachedRetirement() = runTest {
        val retirement = ProviderCopyRetirement()
        val childDeath = retirement.register()
        val cancelledWaiter = launch { retirement.awaitPrevious() }
        runCurrent()
        cancelledWaiter.cancel()
        runCurrent()
        assertTrue(cancelledWaiter.isCompleted)
        assertFalse(childDeath.isCancelled)
        assertFalse(childDeath.isCompleted)
        childDeath.complete(Unit)
        retirement.awaitPrevious()
    }
}
