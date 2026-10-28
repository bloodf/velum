package dev.velum.app.devicewalk

/**
 * Set by [dev.velum.app.devicewalk.DeviceWalkTestRunner] before Application.onCreate
 * when instrumented device-walk CI runs. Production launches never flip this.
 */
object DeviceWalkMode {
    @JvmField
    @Volatile
    var enabled: Boolean = false

    fun isEnabled(): Boolean = enabled
}
