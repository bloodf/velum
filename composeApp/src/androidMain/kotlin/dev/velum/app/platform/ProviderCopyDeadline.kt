package dev.velum.app.platform

import java.util.concurrent.atomic.AtomicBoolean

/** elapsedRealtime includes sleep; short scheduler ticks recheck it after resume. No realtime OS claim. */
internal class ProviderCopyDeadline(
    private val deadline: Long,
    private val now: () -> Long,
    private val terminate: () -> Unit,
) {
    private val fired = AtomicBoolean(false)
    fun check() {
        if (now() >= deadline && fired.compareAndSet(false, true)) terminate()
    }
}
