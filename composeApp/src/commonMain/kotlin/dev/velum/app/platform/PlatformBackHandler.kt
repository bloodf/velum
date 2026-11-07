package dev.velum.app.platform

import androidx.compose.runtime.Composable

/**
 * Intercepts the system Back gesture/button while [enabled] (#300). Android registers with the activity's
 * `OnBackPressedDispatcher`; iOS and desktop have no system Back, so there it does nothing.
 * Use it for in-composition overlays that are not Dialog/ModalBottomSheet (those handle Back themselves).
 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
