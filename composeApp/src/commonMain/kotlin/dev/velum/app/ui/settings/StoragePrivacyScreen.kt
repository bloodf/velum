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
    val scope = rememberCoroutineScope()
    var confirmWipe by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text(strings.wipeAllData) },
            text = { Text(strings.wipeAllDataBody) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmWipe = false
                        scope.launch {
                            container.repository.wipeAllUserData()
                            container.voiceProfileStore.wipeAll()
                            status = strings.wipeAllDataDone
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
        ScreenTopBar(strings.settingsStoragePrivacy, onBack = onBack)
        Text(
            strings.privacyOnDeviceStatement,
            color = VelumInk,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )
        Text(
            strings.privacyNoTelemetry,
            color = VelumInkSecondary,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(24.dp))
        BlackButton(
            strings.exportLibraryBackup,
            {
                scope.launch {
                    val body = container.repository.exportLibraryBackupText()
                    shareText("Velum backup", body)
                    status = strings.exportLibraryBackupDone
                }
            },
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(12.dp))
        OutlineButton(
            strings.wipeAllData,
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
