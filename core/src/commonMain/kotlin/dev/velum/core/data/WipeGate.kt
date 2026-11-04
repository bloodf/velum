package dev.velum.core.data

import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Lets "Wipe all data" stop background work that writes user data (processing, imports, exports, webhooks).
 * Such work runs through [guard]; [wiping] refuses new work, cancels what is running and waits for it.
 */
class WipeGate {
    private val holds = MutableStateFlow(0)
    private val running = MutableStateFlow<Set<Deferred<*>>>(emptySet())
    private val cancelledByWipe = MutableStateFlow<Set<Deferred<*>>>(emptySet())

    val isWiping: Boolean get() = holds.value > 0

    /**
     * Runs [block] unless a wipe is in progress. A wipe cancels [block] without cancelling the caller,
     * so a long-lived caller (an action collector, a worker) keeps running. Null when refused or stopped by a wipe;
     * any other cancellation reaches the caller as usual.
     */
    suspend fun <T> guard(block: suspend () -> T): T? = coroutineScope {
        val work = async(start = CoroutineStart.LAZY) { block() }
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
                    if (work !in cancelledByWipe.value) throw cancelled
                    null
                }
            }
        } finally {
            running.update { it - work }
            cancelledByWipe.update { it - work }
        }
    }

    /**
     * Holds off guarded work for all of [block], including when it fails. Running work is cancelled and
     * awaited for up to [joinTimeout]; [block] learns whether all of it stopped.
     */
    suspend fun <T> wiping(joinTimeout: Duration, block: suspend (allStopped: Boolean) -> T): T {
        holds.update { it + 1 }
        try {
            val work = running.value
            cancelledByWipe.update { it + work }
            work.forEach { it.cancel() }
            val allStopped = withTimeoutOrNull(joinTimeout) { work.forEach { it.join() } } != null
            return block(allStopped)
        } finally {
            holds.update { it - 1 }
        }
    }
}
