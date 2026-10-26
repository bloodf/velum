package dev.velum.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle
import platform.UIKit.UISelectionFeedbackGenerator

@Composable
actual fun rememberHaptics(): Haptics = remember { IosHaptics() }

@Composable
actual fun reduceMotionPreferred(): Boolean =
    UIAccessibilityIsReduceMotionEnabled()

private class IosHaptics : Haptics {
    override fun perform(kind: HapticKind) {
        when (kind) {
            HapticKind.Light -> {
                val g = UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleLight)
                g.prepare()
                g.impactOccurred()
            }
            HapticKind.Medium -> {
                val g = UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleMedium)
                g.prepare()
                g.impactOccurred()
            }
            HapticKind.Selection -> {
                val g = UISelectionFeedbackGenerator()
                g.prepare()
                g.selectionChanged()
            }
        }
    }
}
