package dev.velum.app.devicewalk

import android.app.Application
import android.os.Bundle
import android.os.StrictMode
import androidx.test.runner.AndroidJUnitRunner

/**
 * Enables [DeviceWalkMode] + StrictMode penaltyDeath (disk/network on main) when the
 * instrumentation arg `velumDeviceWalk=true` is set (CI device-walk job).
 */
class DeviceWalkTestRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle?) {
        val walk = arguments?.getString(ARG_DEVICE_WALK).equals("true", ignoreCase = true)
        if (walk) {
            DeviceWalkMode.enabled = true
            installStrictMode()
        }
        super.onCreate(arguments)
    }

    override fun callApplicationOnCreate(app: Application?) {
        // Re-assert before Application.onCreate in case class-loader order differs.
        if (DeviceWalkMode.isEnabled()) {
            installStrictMode()
        }
        super.callApplicationOnCreate(app)
    }

    private fun installStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .penaltyDeath()
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .detectLeakedRegistrationObjects()
                .detectActivityLeaks()
                .penaltyLog()
                .penaltyDeath()
                .build(),
        )
    }

    companion object {
        const val ARG_DEVICE_WALK = "velumDeviceWalk"
    }
}
