package dev.velum.core.data

import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Lets "Wipe all data" stop background work that writes user data (processing, imports, exports, webhooks).
 * Such work runs through [guard]; [wiping] refuses new work, cancels what is running and waits for it.
 */
class WipeGate {
    private val holds = MutableStateFlow(0)
    private val running = MutableStateFlow<Set<Deferred<*>>>(emptySet())

    val isWiping: Boolean get() = holds.value > 0

    /**
     * Runs [block] unless a wipe is in progress. A wipe cancels [block] without cancelling the caller,
     * so a long-lived caller (an action collector, a worker) keeps running. Null when refused or stopped by a wipe;
     * any other cancellation reaches the caller as usual.
     */
    suspend fun <T> guard(block: suspend () -> T): T? = supervisorScope {
        val work = async(start = CoroutineStart.LAZY) { runStoppingForWipe(block) }
        // Registered before the check: a wipe that starts in between still finds and cancels it.
        running.update { it + work }
        try {
            if (isWiping) {
                work.cancel()
                null
            } else {
                try {
                    work.await()
                } catch (cancelled: CancellationException) {
                    ensureActive() // the caller itself was cancelled: keep that
                    if (cancelled !is WipeCancelled) throw cancelled
                    null
                }
            }
        } finally {
            running.update { it - work }
        }
    }

    /** A failure while a wipe stops the work (a status restore on a closing database) is not the caller's to handle. */
    private suspend fun <T> runStoppingForWipe(block: suspend () -> T): T? = try {
        block()
    } catch (failure: Throwable) {
        if (failure is CancellationException || !stoppedByWipe()) throw failure
        println("VelumWipe: work stopped by a wipe failed while unwinding: ${failure::class.simpleName}")
        null
    }

    private suspend fun stoppedByWipe(): Boolean = try {
        currentCoroutineContext().ensureActive()
        false
    } catch (cancelled: CancellationException) {
        cancelled is WipeCancelled
    }

    /**
     * Holds off guarded work for all of [block], including when it fails. Running work is cancelled and
     * awaited for up to [joinTimeout]; [block] learns whether all of it stopped.
     */
    suspend fun <T> wiping(joinTimeout: Duration, block: suspend (allStopped: Boolean) -> T): T {
        holds.update { it + 1 }
        try {
            val work = running.value
            work.forEach { it.cancel(WipeCancelled()) }
            val allStopped = withTimeoutOrNull(joinTimeout) { work.forEach { it.join() } } != null
            return block(allStopped)
        } finally {
            holds.update { it - 1 }
        }
    }
}

/** The cause a wipe cancels work with, which tells its cancellation from any other. */
private class WipeCancelled : CancellationException("Stopped by Wipe all data")
