package dev.velum.app.devicewalk

import kotlin.concurrent.Volatile
import kotlin.jvm.JvmField

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
