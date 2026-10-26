package dev.velum.app.platform

import androidx.compose.runtime.Composable

@Composable
actual fun rememberHaptics(): Haptics = NoOpHaptics

@Composable
actual fun reduceMotionPreferred(): Boolean = false
