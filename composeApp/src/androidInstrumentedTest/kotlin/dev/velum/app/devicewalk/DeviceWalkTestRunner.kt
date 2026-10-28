package dev.velum.app.devicewalk

import android.app.Application
import android.os.Bundle
import android.os.StrictMode
import androidx.test.runner.AndroidJUnitRunner

/**
 * Enables [DeviceWalkMode] + StrictMode penaltyDeath (disk/network on main) when the
 * instrumentation arg `velumDeviceWalk=true` is set (CI device-walk job).
 *
 * StrictMode must be installed **after** [AndroidJUnitRunner.onCreate]: the runner
 * touches disk (dex-maker cache) during setup, and penaltyDeath would crash the
 * instrumentation process before any test runs.
 */
class DeviceWalkTestRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle?) {
        // Use Java equalsIgnoreCase — Kotlin's String.equals(ignoreCase=) compiles to
        // StringsKt and NoSuchMethodErrors when the minified release APK's kotlin
        // stdlib is on the instrumentation classpath (R8 renamed/shrunk methods).
        val walk = arguments?.getString(ARG_DEVICE_WALK)?.equalsIgnoreCase("true") == true
        if (walk) {
            DeviceWalkMode.enabled = true
        }
        super.onCreate(arguments)
        if (walk) {
            installStrictMode()
        }
    }

    override fun callApplicationOnCreate(app: Application?) {
        // Re-assert before Application.onCreate so app main-thread disk/network dies.
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
