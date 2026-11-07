@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.velum.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.AVFAudio.AVAudioSession
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

@Composable
actual fun rememberPermissionRequester(
    permission: AppPermission,
    rationaleTitle: String,
    rationaleBody: String,
): (onResult: (PermissionOutcome) -> Unit) -> Unit =
    remember(permission) {
        { onResult ->
            if (permission == AppPermission.RecordAudio) {
                AVAudioSession.sharedInstance().requestRecordPermission { granted ->
                    dispatch_async(dispatch_get_main_queue()) {
                        onResult(if (granted) PermissionOutcome.Granted else PermissionOutcome.Denied)
                    }
                }
            } else {
                onResult(PermissionOutcome.NotRequired)
            }
        }
    }
