package dev.velum.app.platform

/**
 * Platform keep-alive for in-flight Plaud session downloads (BLE / SoftAP).
 * Android promotes a `connectedDevice` foreground service; other targets no-op.
 */
expect object SessionDownloadKeepAlive {
    fun start()

    fun stop()
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
    private val startPlatform: () -> Unit = { SessionDownloadKeepAlive.start() },
    private val stopPlatform: () -> Unit = { SessionDownloadKeepAlive.stop() },
) {
    private val lock = Any()
    private var active: Boolean = false

    val isActive: Boolean
        get() = synchronized(lock) { active }

    fun onDownloadBegin() {
        val shouldStart = synchronized(lock) {
            if (active) {
                false
            } else {
                active = true
                true
            }
        }
        if (shouldStart) startPlatform()
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
