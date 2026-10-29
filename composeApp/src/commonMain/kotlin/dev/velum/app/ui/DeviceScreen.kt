package dev.velum.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import dev.velum.app.i18n.DeviceUiCopy
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.platform.AppPermission
import dev.velum.app.platform.PermissionOutcome
import dev.velum.app.platform.rememberPermissionRequester
import dev.velum.app.ui.components.ScreenTopBar
import dev.velum.ble.BleConnectionState
import dev.velum.ble.BleTransport
import dev.velum.core.model.ConnectedDevice
import dev.velum.protocol.DeviceCapabilityRegistry

@Composable
fun DeviceScreen(
    ble: BleTransport,
    viewModel: DeviceViewModel,
    onBack: (() -> Unit)? = null,
    onOpenRecording: (String) -> Unit = {},
) {
    val state by viewModel.connectionState.collectAsState()
    val devices by viewModel.devices.collectAsState()
    val sessionUi by viewModel.sessionUi.collectAsState()
    val strings = LocalStrings.current
    val requestScan = rememberPermissionRequester(
        permission = AppPermission.BluetoothScan,
        rationaleTitle = strings.ux.scan,
        rationaleBody = DeviceUiCopy.blePermissionBody(strings.localeTag),
    )
    val requestConnect = rememberPermissionRequester(
        permission = AppPermission.BluetoothConnect,
        rationaleTitle = strings.ux.scan,
        rationaleBody = DeviceUiCopy.blePermissionBody(strings.localeTag),
    )
    DeviceScreenContent(
        state = state,
        devices = devices,
        sessionUi = sessionUi,
        bleAvailable = ble.isAvailable,
        onBack = onBack,
        onStartScan = {
            requestScan { scanOutcome ->
                if (scanOutcome == PermissionOutcome.Denied) return@requestScan
                requestConnect { connectOutcome ->
                    if (connectOutcome != PermissionOutcome.Denied) {
                        viewModel.startScan()
                    }
                }
            }
        },
        onStopScan = { viewModel.stopScan() },
        onDisconnect = { viewModel.disconnect() },
        onConnect = { id ->
            requestConnect { outcome ->
                if (outcome != PermissionOutcome.Denied) viewModel.connect(id)
            }
        },
        onDownloadSession = { viewModel.downloadSession(it) },
        onCancelDownload = { viewModel.cancelDownload() },
        onOpenRecording = onOpenRecording,
    )
}

/** Stateless device UI — used by [DeviceScreen] and Roborazzi fixtures. */
@Composable
fun DeviceScreenContent(
    state: BleConnectionState,
    devices: List<ConnectedDevice>,
    sessionUi: DeviceSessionUi,
    bleAvailable: Boolean,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onDisconnect: () -> Unit,
    onConnect: (String) -> Unit,
    onBack: (() -> Unit)? = null,
    onDownloadSession: (Long) -> Unit = {},
    onCancelDownload: () -> Unit = {},
    onOpenRecording: (String) -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                        MaterialTheme.colorScheme.background,
                    ),
                ),
            )
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        val strings = LocalStrings.current
        val locale = strings.localeTag
        if (onBack != null) {
            ScreenTopBar(title = strings.settingsUi.sectionDevices, onBack = onBack)
            Spacer(Modifier.height(8.dp))
        } else {
            Text(
                text = "Velum",
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = DeviceUiCopy.tagline(locale),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
        }

        StatusBanner(state = state, bleAvailable = bleAvailable, localeTag = locale)
        sessionUi.statusMessage?.let { msg ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = buildString {
                    append(msg)
                    sessionUi.batteryPercent?.let { append(" · $it%") }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (sessionUi.wifiFastPath) {
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(14.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        DeviceUiCopy.wifiFastTitle(locale),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        DeviceUiCopy.wifiFastBody(locale),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (state is BleConnectionState.Connected &&
            sessionUi.protocolVersion != null &&
            sessionUi.sessions.isEmpty() &&
            sessionUi.downloadingSessionId == null
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = DeviceUiCopy.sessionsOnDevice(locale),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = DeviceUiCopy.noSessionsOnDevice(locale),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (sessionUi.sessions.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = DeviceUiCopy.sessionsOnDevice(locale),
                style = MaterialTheme.typography.titleLarge,
            )
            sessionUi.sessions.take(24).forEach { entry ->
                val primary = resolveDeviceSessionRowAction(
                    sessionId = entry.sessionId,
                    libraryRecordingBySessionId = sessionUi.libraryRecordingBySessionId,
                    downloadingSessionId = sessionUi.downloadingSessionId,
                )
                val redownload = resolveDeviceSessionRedownloadAction(
                    sessionId = entry.sessionId,
                    libraryRecordingBySessionId = sessionUi.libraryRecordingBySessionId,
                )
                val inLibrary = primary is DeviceSessionRowAction.Open
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "#${entry.sessionId} · ${formatSessionSize(entry.sizeBytes)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (inLibrary) {
                                Text(
                                    text = DeviceUiCopy.inLibrary(locale),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        when (primary) {
                            is DeviceSessionRowAction.CancelDownload -> {
                                TextButton(onClick = onCancelDownload) {
                                    Text(strings.cancel)
                                }
                            }
                            is DeviceSessionRowAction.Open -> {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Button(
                                        onClick = { onOpenRecording(primary.recordingId) },
                                        enabled = sessionUi.downloadingSessionId == null,
                                    ) {
                                        Text(DeviceUiCopy.openDownloaded(locale))
                                    }
                                    val secondary = redownload
                                    if (secondary != null) {
                                        TextButton(
                                            onClick = { onDownloadSession(secondary.sessionId) },
                                            enabled = sessionUi.downloadingSessionId == null,
                                        ) {
                                            Text(DeviceUiCopy.redownload(locale))
                                        }
                                    }
                                }
                            }
                            is DeviceSessionRowAction.Download -> {
                                Button(
                                    onClick = { onDownloadSession(primary.sessionId) },
                                    enabled = sessionUi.downloadingSessionId == null,
                                ) {
                                    Text(strings.download)
                                }
                            }
                            is DeviceSessionRowAction.Redownload -> {
                                // Primary resolve never returns Redownload (secondary only).
                                error("unreachable: Redownload is secondary")
                            }
                        }
                    }
                    if (primary is DeviceSessionRowAction.CancelDownload) {
                        val progress = sessionUi.downloadProgress ?: 0f
                        val percent = (progress * 100).toInt().coerceIn(0, 99)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth().height(4.dp),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = DeviceUiCopy.downloadPercent(locale, percent),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onStartScan,
                enabled = bleAvailable && state !is BleConnectionState.Connecting,
            ) {
                Text(if (state is BleConnectionState.Scanning) strings.ux.scanning else strings.ux.scan)
            }
            OutlinedButton(
                onClick = onStopScan,
                enabled = state is BleConnectionState.Scanning,
            ) {
                Text(strings.ux.stop)
            }
            if (state is BleConnectionState.Connected) {
                TextButton(onClick = onDisconnect) {
                    Text(strings.disconnect)
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            text = strings.ux.nearbyDevices,
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(8.dp))

        AnimatedVisibility(
            visible = devices.isEmpty(),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            EmptyDeviceHint(bleAvailable = bleAvailable, localeTag = locale)
        }

        LazyColumn(
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(devices, key = { it.id }) { device ->
                DeviceRow(device = device, onConnect = { onConnect(device.id) })
            }
        }
    }
}

@Composable
private fun StatusBanner(
    state: BleConnectionState,
    bleAvailable: Boolean,
    localeTag: String,
) {
    val text = if (!bleAvailable) {
        DeviceUiCopy.bleStubbed(localeTag)
    } else {
        when (state) {
            is BleConnectionState.Idle -> DeviceUiCopy.readyToScan(localeTag)
            is BleConnectionState.Scanning -> DeviceUiCopy.scanningRecorders(localeTag)
            is BleConnectionState.Connecting -> DeviceUiCopy.connectingTo(localeTag, state.deviceId)
            is BleConnectionState.Connected -> {
                val d = state.device
                val model = DeviceCapabilityRegistry.detect(
                    productCode = d.modelCode,
                    advertisedName = d.name,
                ).displayName
                DeviceUiCopy.connected(localeTag, "${d.name ?: d.serial ?: d.id} · $model")
            }
            is BleConnectionState.Failed -> DeviceUiCopy.connectionFailed(localeTag)
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
            .padding(16.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun EmptyDeviceHint(bleAvailable: Boolean, localeTag: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = if (bleAvailable) {
                DeviceUiCopy.noDevicesYet(localeTag)
            } else {
                DeviceUiCopy.desktopBleLater(localeTag)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DeviceRow(device: ConnectedDevice, onConnect: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val caps = DeviceCapabilityRegistry.detect(
            productCode = device.modelCode,
            advertisedName = device.name,
        )
        Text(
            text = device.name ?: device.serial ?: caps.displayName,
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = caps.displayName,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        val meta = buildString {
            device.serial?.let { append(it) }
            device.protocolVersion?.let {
                if (isNotEmpty()) append(" · ")
                append("protocol $it")
            }
            if (isNotEmpty()) append(" · ")
            val locale = LocalStrings.current.localeTag
            append(
                if (caps.wifiFastTransfer) DeviceUiCopy.wifiCapable(locale)
                else DeviceUiCopy.bleOnly(locale),
            )
            device.rssi?.let {
                append(" · ")
                append("${it} dBm")
            }
        }
        if (meta.isNotEmpty()) {
            Text(
                text = meta,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (device.id.isNotBlank()) {
            val strings = LocalStrings.current
            Button(onClick = onConnect) { Text(strings.connect) }
        }
    }
}

private fun formatSessionSize(bytes: Long): String = when {
    bytes >= 1_000_000 -> "${bytes / 1_000_000} MB"
    bytes >= 1_000 -> "${bytes / 1_000} KB"
    else -> "$bytes B"
}
