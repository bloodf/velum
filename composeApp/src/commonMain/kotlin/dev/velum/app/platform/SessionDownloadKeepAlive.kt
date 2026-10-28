package dev.velum.app.platform

/**
 * Result of requesting the platform download keep-alive (Android FGS).
 */
enum class SessionDownloadKeepAliveStart {
    /** FGS start requested (or no-op platform). */
    Started,

    /** Caller-side gate: BLUETOOTH_CONNECT missing — service was not started. */
    DeniedBluetooth,

    /** startForegroundService threw / other hard failure before the service ran. */
    Failed,
}

/**
 * Platform keep-alive for in-flight Plaud session downloads (BLE / SoftAP).
 * Android promotes a `connectedDevice` foreground service; other targets no-op.
 */
expect object SessionDownloadKeepAlive {
    fun start(): SessionDownloadKeepAliveStart

    fun stop()

    /**
     * Invoked when the service had to abandon `connectedDevice` promotion
     * (e.g. permission revoked) after [start] already returned [SessionDownloadKeepAliveStart.Started].
     */
    fun setPromoteFailureHandler(handler: ((message: String) -> Unit)?)
}

/**
 * Idempotent start/stop gate so shared download code can bookend a transfer
 * without leaking a platform keep-alive across repeated downloads.
 *
 * Thread-safe via a synchronized CAS on [active]; safe to call from the
 * [dev.velum.app.download.SessionDownloadManager] coroutine threads (not Main-only).
 *
 * [startPlatform] / [stopPlatform] are injectable for JVM unit tests.
 */
class SessionDownloadKeepAliveGate(
    private val startPlatform: () -> SessionDownloadKeepAliveStart = {
        SessionDownloadKeepAlive.start()
    },
    private val stopPlatform: () -> Unit = { SessionDownloadKeepAlive.stop() },
) {
    private val lock = Any()
    private var active: Boolean = false

    val isActive: Boolean
        get() = synchronized(lock) { active }

    fun onDownloadBegin(): SessionDownloadKeepAliveStart {
        val shouldStart = synchronized(lock) {
            if (active) {
                false
            } else {
                active = true
                true
            }
        }
        if (!shouldStart) return SessionDownloadKeepAliveStart.Started
        val result = startPlatform()
        if (result != SessionDownloadKeepAliveStart.Started) {
            synchronized(lock) { active = false }
        }
        return result
    }

    fun onDownloadEnd() {
        val shouldStop = synchronized(lock) {
            if (!active) {
                false
            } else {
                active = false
                true
            }
        }
        if (shouldStop) stopPlatform()
    }
}
