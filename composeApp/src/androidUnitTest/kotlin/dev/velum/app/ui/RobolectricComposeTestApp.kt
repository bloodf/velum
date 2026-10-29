package dev.velum.app.ui

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import org.robolectric.Shadows

/**
 * Registers [ComponentActivity] for Robolectric [androidx.compose.ui.test.junit4.createComposeRule]
 * without shipping that activity in the debug APK (device-walk installs debug).
 *
 * Putting ComponentActivity in `src/debug/AndroidManifest.xml` fixed unit tests but
 * changed the walk APK vs main/#86 and correlated with DEBUG walk
 * `assertUiChrome` failures after storage (a11y window timeout, no Files chrome).
 */
class RobolectricComposeTestApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val component = ComponentName(this, ComponentActivity::class.java)
        Shadows.shadowOf(packageManager).addActivityIfNotPresent(component)
    }
}
