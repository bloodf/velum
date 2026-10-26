package dev.velum.app.platform

import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView

@Composable
actual fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { AndroidHaptics(view) }
}

@Composable
actual fun reduceMotionPreferred(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        val scale = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
        scale == 0f
    }
}

private class AndroidHaptics(
    private val view: View,
) : Haptics {
    override fun perform(kind: HapticKind) {
        val constant = when (kind) {
            HapticKind.Light ->
                if (Build.VERSION.SDK_INT >= 30) {
                    HapticFeedbackConstants.CONFIRM
                } else {
                    HapticFeedbackConstants.KEYBOARD_TAP
                }
            HapticKind.Medium -> HapticFeedbackConstants.LONG_PRESS
            HapticKind.Selection ->
                if (Build.VERSION.SDK_INT >= 27) {
                    HapticFeedbackConstants.CLOCK_TICK
                } else {
                    HapticFeedbackConstants.KEYBOARD_TAP
                }
        }
        view.performHapticFeedback(constant)
    }
}
