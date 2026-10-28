package dev.velum.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Key
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.padding
import dev.velum.app.di.AppContainer
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.ui.components.ChevronRow
import dev.velum.app.ui.components.ScreenTopBar
import dev.velum.app.ui.components.SectionLabel
import dev.velum.app.ui.components.ToggleRow
import dev.velum.app.ui.theme.VelumInkSecondary
import dev.velum.app.ui.theme.VelumSurface
import kotlinx.coroutines.launch

@Composable
fun DevicesSettingsScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onDevice: () -> Unit,
    onImportKeys: () -> Unit,
) {
    val strings = LocalStrings.current
    val prefs by container.settingsRepository.preferences.collectAsState()
    val scope = rememberCoroutineScope()

    Column(
        Modifier
            .fillMaxSize()
            .background(VelumSurface)
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenTopBar(strings.settingsUi.sectionDevices, onBack = onBack)
        Text(
            strings.settingsUi.devicesHint,
            color = VelumInkSecondary,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        SectionLabel(strings.deviceManagement)
        ChevronRow(strings.scanDevices, onDevice, icon = Icons.Outlined.Devices)
        ChevronRow(strings.importKeys.title, onImportKeys, icon = Icons.Outlined.Key, showDivider = false)
        Spacer(Modifier.height(8.dp))
        SectionLabel(strings.settingsUi.wifiTransfer)
        Text(
            strings.settingsUi.preferWifiFastPathHint,
            color = VelumInkSecondary,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        ToggleRow(
            strings.settingsUi.autoSyncWatchedFolders,
            prefs.autoSyncWatchedFolders,
            { enabled -> scope.launch { container.settingsRepository.setAutoSyncWatchedFolders(enabled) } },
            strings.settingsUi.autoSyncWatchedFoldersHint,
            showDivider = false,
        )
        Spacer(Modifier.height(40.dp))
    }
}
