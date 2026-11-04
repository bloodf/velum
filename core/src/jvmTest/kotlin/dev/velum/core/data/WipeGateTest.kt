package dev.velum.core.data

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class WipeGateTest {
    @Test fun work_is_refused_while_a_wipe_runs_and_allowed_after() = runBlocking {
        val gate = WipeGate()
        var ran = false
        gate.wiping(1.seconds) { assertNull(gate.guard { ran = true }) }
        assertFalse(ran)
        assertEquals(1, gate.guard { 1 })
    }

    @Test fun a_wipe_cancels_running_work_and_waits_for_it_without_cancelling_the_caller() = runBlocking {
        val gate = WipeGate()
        val started = CompletableDeferred<Unit>()
        val unwound = AtomicBoolean(false)
        var result: Any? = "not set"
        val caller = launch(Dispatchers.Default) {
            result = gate.guard {
                try {
                    started.complete(Unit)
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) { delay(200) }
                    unwound.set(true)
                }
            }
        }
        started.await()
        val allStopped = gate.wiping(5.seconds) { stopped ->
            assertTrue(unwound.get(), "the wipe went ahead before the work had unwound")
            stopped
        }
        assertTrue(allStopped)
        caller.join()
        assertFalse(caller.isCancelled, "the caller must keep running")
        assertNull(result)
    }

    @Test fun work_that_outlasts_the_wait_is_reported() = runBlocking {
        val gate = WipeGate()
        val started = CompletableDeferred<Unit>()
        val caller = launch(Dispatchers.Default) {
            gate.guard { withContext(NonCancellable) { started.complete(Unit); delay(1_000) } }
        }
        started.await()
        assertFalse(gate.wiping(100.milliseconds) { it })
        caller.join()
    }

    @Test fun a_failed_wipe_reopens_the_gate() = runBlocking {
        val gate = WipeGate()
        assertFailsWith<IllegalStateException> { gate.wiping(1.seconds) { error("synthetic wipe failure") } }
        assertEquals(1, gate.guard { 1 })
    }

    @Test fun a_cancellation_that_is_not_from_a_wipe_is_rethrown() {
        val gate = WipeGate()
        assertFailsWith<CancellationException> {
            runBlocking { gate.guard { throw CancellationException("synthetic cancel") } }
        }
    }
}
