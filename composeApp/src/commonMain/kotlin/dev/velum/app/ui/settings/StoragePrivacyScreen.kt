package dev.velum.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.velum.app.di.AppContainer
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.platform.shareText
import dev.velum.app.ui.components.BlackButton
import dev.velum.app.ui.components.OutlineButton
import dev.velum.app.ui.components.ScreenTopBar
import dev.velum.app.ui.components.SectionLabel
import dev.velum.app.ui.components.ToggleRow
import dev.velum.app.ui.theme.VelumInk
import dev.velum.app.ui.theme.VelumInkSecondary
import dev.velum.app.ui.theme.VelumSurface
import kotlinx.coroutines.launch

@Composable
fun StoragePrivacyScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val strings = LocalStrings.current
    val prefs by container.settingsRepository.preferences.collectAsState()
    val scope = rememberCoroutineScope()
    var confirmWipe by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var usageLabel by remember { mutableStateOf(strings.settingsUi.storageUsageLoading) }

    LaunchedEffect(container) {
        val usage = container.repository.storageUsage()
        usageLabel = strings.settingsUi.storageUsageFormat
            .replace("%d", usage.recordingCount.toString())
                .replace("%s", formatStorageBytes(usage.bytesClaimed))
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text(strings.settingsUi.wipeAllData) },
            text = { Text(strings.settingsUi.wipeAllDataBody) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmWipe = false
                        scope.launch {
                            container.repository.wipeAllUserData()
                            container.voiceProfileStore.wipeAll()
                            status = strings.settingsUi.wipeAllDataDone
                            val usage = container.repository.storageUsage()
                            usageLabel = strings.settingsUi.storageUsageFormat
                                .replace("%d", usage.recordingCount.toString())
                                .replace("%s", formatStorageBytes(usage.bytesClaimed))
                        }
                    },
                ) { Text(strings.confirm) }
            },
            dismissButton = {
                TextButton(onClick = { confirmWipe = false }) { Text(strings.cancel) }
            },
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(VelumSurface)
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenTopBar(strings.settingsUi.storagePrivacy, onBack = onBack)
        Text(
            strings.settingsUi.privacyOnDeviceStatement,
            color = VelumInk,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )
        Text(
            strings.settingsUi.privacyNoTelemetry,
            color = VelumInkSecondary,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(16.dp))
        SectionLabel(strings.settingsUi.storageUsage)
        Text(
            usageLabel,
            color = VelumInk,
            fontSize = 15.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        Spacer(Modifier.height(8.dp))
        ToggleRow(
            strings.settingsUi.deleteAudioAfterProcessing,
            prefs.deleteAudioAfterProcessing,
            { enabled ->
                scope.launch { container.settingsRepository.setDeleteAudioAfterProcessing(enabled) }
            },
            strings.settingsUi.deleteAudioAfterProcessingHint,
            showDivider = false,
        )
        Spacer(Modifier.height(24.dp))
        BlackButton(
            strings.settingsUi.exportLibraryBackup,
            {
                scope.launch {
                    val body = container.repository.exportLibraryBackupText()
                    shareText("Velum backup", body)
                    status = strings.settingsUi.exportLibraryBackupDone
                }
            },
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(12.dp))
        OutlineButton(
            strings.settingsUi.wipeAllData,
            { confirmWipe = true },
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        )
        status?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, color = VelumInkSecondary, modifier = Modifier.padding(horizontal = 20.dp))
        }
        Spacer(Modifier.height(40.dp))
    }
}

private fun formatStorageBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "${"%.1f".format(kb)} KB"
    val mb = kb / 1024.0
    if (mb < 1024) return "${"%.1f".format(mb)} MB"
    return "${"%.2f".format(mb / 1024.0)} GB"
}
