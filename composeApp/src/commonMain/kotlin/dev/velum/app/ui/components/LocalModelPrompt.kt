package dev.velum.app.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import dev.velum.app.i18n.LocalStrings

@Composable
fun LocalModelMissingDialog(
    modelDisplayName: String,
    onDownload: () -> Unit,
    onSwitchToApi: () -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.localModelMissingTitle) },
        text = {
            Text(strings.localModelMissingBody.replace("%s", modelDisplayName))
        },
        confirmButton = {
            TextButton(onClick = onDownload) { Text(strings.downloadModel) }
        },
        dismissButton = {
            TextButton(onClick = onSwitchToApi) { Text(strings.switchToApi) }
        },
    )
}
