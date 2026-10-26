package dev.velum.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

enum class HapticKind {
    Light,
    Medium,
    Selection,
}

interface Haptics {
    fun perform(kind: HapticKind)
}

object NoOpHaptics : Haptics {
    override fun perform(kind: HapticKind) = Unit
}

val LocalHaptics = staticCompositionLocalOf<Haptics> { NoOpHaptics }

@Composable
expect fun rememberHaptics(): Haptics

/** System "reduce motion" / animator scale off. */
@Composable
expect fun reduceMotionPreferred(): Boolean
