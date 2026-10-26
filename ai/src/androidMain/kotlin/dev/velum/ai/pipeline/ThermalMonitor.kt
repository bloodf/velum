package dev.velum.ai.pipeline

import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.delay

internal object ThermalMonitor {
    fun isSevereOrCritical(powerManager: PowerManager?): Boolean {
        if (powerManager == null) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val status = powerManager.currentThermalStatus
        return status == PowerManager.THERMAL_STATUS_SEVERE ||
            status == PowerManager.THERMAL_STATUS_CRITICAL
    }

    /**
     * Suspend until thermal status drops below SEVERE, using exponential backoff.
     * Invokes [onPaused] before each wait so callers can persist / emit PausedThermal.
     */
    suspend fun awaitCool(
        powerManager: PowerManager?,
        onPaused: suspend (message: String) -> Unit,
        initialBackoffMs: Long = 15_000L,
        maxBackoffMs: Long = 120_000L,
    ) {
        if (!isSevereOrCritical(powerManager)) return
        var backoff = initialBackoffMs
        while (isSevereOrCritical(powerManager)) {
            onPaused("Device thermal status severe/critical — pausing AI work")
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(maxBackoffMs)
        }
    }
}
