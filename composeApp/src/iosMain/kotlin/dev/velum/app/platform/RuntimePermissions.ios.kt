package dev.velum.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
actual fun rememberPermissionRequester(
    permission: AppPermission,
    rationaleTitle: String,
    rationaleBody: String,
): (onResult: (PermissionOutcome) -> Unit) -> Unit =
    remember(permission) {
        { onResult -> onResult(PermissionOutcome.NotRequired) }
    }
