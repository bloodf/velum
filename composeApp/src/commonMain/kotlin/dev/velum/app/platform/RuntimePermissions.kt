package dev.velum.app.platform

import androidx.compose.runtime.Composable

enum class AppPermission {
    RecordAudio,
    ReadCalendar,
    PostNotifications,
    /** Android 12+ `BLUETOOTH_SCAN` (neverForLocation). Not required below API 31. */
    BluetoothScan,
    /** Android 12+ `BLUETOOTH_CONNECT`. Not required below API 31. */
    BluetoothConnect,
}

sealed class PermissionOutcome {
    data object Granted : PermissionOutcome()
    data object Denied : PermissionOutcome()
    /** Permission not applicable on this platform (desktop) or below API 33. */
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
