package dev.velum.app.platform

import androidx.compose.runtime.Composable

enum class AppPermission {
    RecordAudio,
    ReadCalendar,
}

sealed class PermissionOutcome {
    data object Granted : PermissionOutcome()
    data object Denied : PermissionOutcome()
    /** Permission not applicable on this platform (desktop). */
    data object NotRequired : PermissionOutcome()
}

/**
 * Requests a dangerous permission with a Compose-side rationale dialog first.
 * Desktop/iOS return [PermissionOutcome.NotRequired] / platform-specific stubs.
 */
@Composable
expect fun rememberPermissionRequester(
    permission: AppPermission,
    rationaleTitle: String,
    rationaleBody: String,
): (onResult: (PermissionOutcome) -> Unit) -> Unit
