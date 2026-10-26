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

@Composable
fun DeviceScreen(
    ble: BleTransport,
    viewModel: DeviceViewModel,
) {
    val state by viewModel.connectionState.collectAsState()
    val devices by viewModel.devices.collectAsState()

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

        StatusBanner(state = state, bleAvailable = ble.isAvailable)

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { viewModel.startScan() },
                enabled = ble.isAvailable && state !is BleConnectionState.Connecting,
            ) {
                Text(if (state is BleConnectionState.Scanning) "Scanning…" else "Scan")
            }
            OutlinedButton(
                onClick = { viewModel.stopScan() },
                enabled = state is BleConnectionState.Scanning,
            ) {
                Text("Stop")
            }
            if (state is BleConnectionState.Connected) {
                TextButton(onClick = { viewModel.disconnect() }) {
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
            EmptyDeviceHint(bleAvailable = ble.isAvailable)
        }

        LazyColumn(
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(devices, key = { it.id }) { device ->
                DeviceRow(device = device, onConnect = { viewModel.connect(device.id) })
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
            is BleConnectionState.Scanning -> "Scanning for Plaud service 0x1910…"
            is BleConnectionState.Connecting -> "Connecting to ${state.deviceId}…"
            is BleConnectionState.Connected -> {
                val d = state.device
                "Connected: ${d.name ?: d.serial ?: d.id}"
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
        Text(
            text = device.name ?: device.serial ?: "Unknown device",
            style = MaterialTheme.typography.titleLarge,
        )
        val meta = buildString {
            device.serial?.let { append(it) }
            device.protocolVersion?.let {
                if (isNotEmpty()) append(" · ")
                append("protocol $it")
            }
            device.rssi?.let {
                if (isNotEmpty()) append(" · ")
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
        Button(onClick = onConnect) { Text("Connect") }
    }
}
