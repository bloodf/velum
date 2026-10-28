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
 * [startPlatform] / [stopPlatform] are injectable for JVM unit tests.
 */
class SessionDownloadKeepAliveGate(
    private val startPlatform: () -> Unit = { SessionDownloadKeepAlive.start() },
    private val stopPlatform: () -> Unit = { SessionDownloadKeepAlive.stop() },
) {
    private var active: Boolean = false

    val isActive: Boolean
        get() = active

    fun onDownloadBegin() {
        if (active) return
        active = true
        startPlatform()
    }

    fun onDownloadEnd() {
        if (!active) return
        active = false
        stopPlatform()
    }
}
