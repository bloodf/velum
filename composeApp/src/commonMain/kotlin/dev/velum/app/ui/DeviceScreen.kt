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
import dev.velum.ble.BleConnectionState
import dev.velum.ble.BleTransport
import dev.velum.core.model.ConnectedDevice
import dev.velum.protocol.DeviceCapabilityRegistry

@Composable
fun DeviceScreen(
    ble: BleTransport,
    viewModel: DeviceViewModel,
) {
    val state by viewModel.connectionState.collectAsState()
    val devices by viewModel.devices.collectAsState()
    val sessionUi by viewModel.sessionUi.collectAsState()
    DeviceScreenContent(
        state = state,
        devices = devices,
        sessionUi = sessionUi,
        bleAvailable = ble.isAvailable,
        onStartScan = { viewModel.startScan() },
        onStopScan = { viewModel.stopScan() },
        onDisconnect = { viewModel.disconnect() },
        onConnect = { viewModel.connect(it) },
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
        Text(
            text = "Velum",
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = "Your recorder, your models, your notes.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))

        StatusBanner(state = state, bleAvailable = bleAvailable)
        sessionUi.statusMessage?.let { msg ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = buildString {
                    append(msg)
                    sessionUi.batteryPercent?.let { append(" · battery $it%") }
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
                        "Wi‑Fi fast transfer",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "SoftAP + WebSocket ready — large sessions skip slow BLE bulk transfer.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (sessionUi.sessions.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Sessions on device",
                style = MaterialTheme.typography.titleLarge,
            )
            sessionUi.sessions.take(8).forEach { entry ->
                Text(
                    text = "#${entry.sessionId} · ${entry.sizeBytes} bytes",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onStartScan,
                enabled = bleAvailable && state !is BleConnectionState.Connecting,
            ) {
                Text(if (state is BleConnectionState.Scanning) "Scanning…" else "Scan")
            }
            OutlinedButton(
                onClick = onStopScan,
                enabled = state is BleConnectionState.Scanning,
            ) {
                Text("Stop")
            }
            if (state is BleConnectionState.Connected) {
                TextButton(onClick = onDisconnect) {
                    Text("Disconnect")
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            text = "Nearby devices",
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(8.dp))

        AnimatedVisibility(
            visible = devices.isEmpty(),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            EmptyDeviceHint(bleAvailable = bleAvailable)
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
private fun StatusBanner(state: BleConnectionState, bleAvailable: Boolean) {
    val text = if (!bleAvailable) {
        "BLE on this platform is stubbed. Pairing works on Android and iOS."
    } else {
        when (state) {
            is BleConnectionState.Idle -> "Ready to scan for a compatible recorder."
            is BleConnectionState.Scanning -> "Scanning for compatible recorders…"
            is BleConnectionState.Connecting -> "Connecting to ${state.deviceId}…"
            is BleConnectionState.Connected -> {
                val d = state.device
                val model = DeviceCapabilityRegistry.detect(
                    productCode = d.modelCode,
                    advertisedName = d.name,
                ).displayName
                "Connected: ${d.name ?: d.serial ?: d.id} · $model"
            }
            is BleConnectionState.Failed -> state.message
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
private fun EmptyDeviceHint(bleAvailable: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = if (bleAvailable) {
                "No devices yet. Power on your recorder and tap Scan."
            } else {
                "Desktop BLE comes later. Build the Android app to talk to hardware."
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
            append(if (caps.wifiFastTransfer) "Wi‑Fi capable" else "BLE only")
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
            Button(onClick = onConnect) { Text("Connect") }
        }
    }
}
