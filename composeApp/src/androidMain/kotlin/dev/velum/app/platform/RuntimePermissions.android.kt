package dev.velum.app.platform

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.velum.app.i18n.LocalStrings
import androidx.core.content.ContextCompat

@Composable
actual fun rememberPermissionRequester(
    permission: AppPermission,
    rationaleTitle: String,
    rationaleBody: String,
): (onResult: (PermissionOutcome) -> Unit) -> Unit {
    val context = LocalContext.current
    val strings = LocalStrings.current
    val manifestPermission = when (permission) {
        AppPermission.RecordAudio -> Manifest.permission.RECORD_AUDIO
        AppPermission.ReadCalendar -> Manifest.permission.READ_CALENDAR
        AppPermission.PostNotifications ->
            if (Build.VERSION.SDK_INT >= 33) {
                Manifest.permission.POST_NOTIFICATIONS
            } else {
                null
            }
        AppPermission.BluetoothScan ->
            if (Build.VERSION.SDK_INT >= 31) {
                Manifest.permission.BLUETOOTH_SCAN
            } else {
                // Pre-31 scan used legacy BLUETOOTH + location (manifest maxSdkVersion=30).
                Manifest.permission.ACCESS_FINE_LOCATION
            }
        AppPermission.BluetoothConnect ->
            if (Build.VERSION.SDK_INT >= 31) {
                Manifest.permission.BLUETOOTH_CONNECT
            } else {
                null
            }
    }
    var pendingCallback by remember { mutableStateOf<((PermissionOutcome) -> Unit)?>(null) }
    var showRationale by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val cb = pendingCallback
        pendingCallback = null
        cb?.invoke(if (granted) PermissionOutcome.Granted else PermissionOutcome.Denied)
    }

    if (showRationale && manifestPermission != null) {
        AlertDialog(
            onDismissRequest = {
                showRationale = false
                val cb = pendingCallback
                pendingCallback = null
                cb?.invoke(PermissionOutcome.Denied)
            },
            title = { Text(rationaleTitle) },
            text = { Text(rationaleBody) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRationale = false
                        launcher.launch(manifestPermission)
                    },
                ) { Text(strings.ok) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showRationale = false
                        val cb = pendingCallback
                        pendingCallback = null
                        cb?.invoke(PermissionOutcome.Denied)
                    },
                ) { Text(strings.cancel) }
            },
        )
    }

    return remember(manifestPermission, rationaleTitle, rationaleBody) {
        { onResult ->
            if (manifestPermission == null) {
                onResult(PermissionOutcome.NotRequired)
                return@remember
            }
            val granted = ContextCompat.checkSelfPermission(context, manifestPermission) ==
                PackageManager.PERMISSION_GRANTED
            if (granted) {
                onResult(PermissionOutcome.Granted)
            } else {
                pendingCallback = onResult
                showRationale = true
            }
        }
    }
}
