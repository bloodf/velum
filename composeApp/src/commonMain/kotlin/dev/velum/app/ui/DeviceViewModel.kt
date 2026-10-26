package dev.velum.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.velum.ble.BleConnectionState
import dev.velum.ble.BleTransport
import dev.velum.ble.client.PlaudDeviceClient
import dev.velum.ble.crypto.createSessionCipher
import dev.velum.core.model.ConnectedDevice
import dev.velum.core.security.DeviceKeyImporter
import dev.velum.protocol.SessionEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DeviceSessionUi(
    val batteryPercent: Int? = null,
    val sessions: List<SessionEntry> = emptyList(),
    val statusMessage: String? = null,
    val protocolVersion: Int? = null,
)

class DeviceViewModel(
    private val ble: BleTransport,
    private val keyImporter: DeviceKeyImporter? = null,
) : ViewModel() {
    val connectionState: StateFlow<BleConnectionState> =
        ble.connectionState.stateIn(viewModelScope, SharingStarted.Eagerly, BleConnectionState.Idle)

    val devices: StateFlow<List<ConnectedDevice>> =
        ble.discoveredDevices.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _sessionUi = MutableStateFlow(DeviceSessionUi())
    val sessionUi: StateFlow<DeviceSessionUi> = _sessionUi.asStateFlow()

    private var client: PlaudDeviceClient? = null

    fun startScan() {
        viewModelScope.launch { ble.startScan() }
    }

    fun stopScan() {
        viewModelScope.launch { ble.stopScan() }
    }

    fun connect(deviceId: String) {
        viewModelScope.launch {
            ble.connect(deviceId)
            runProtocolIfKeysPresent()
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            client?.close()
            client = null
            _sessionUi.value = DeviceSessionUi()
            ble.disconnect()
        }
    }

    private suspend fun runProtocolIfKeysPresent() {
        val importer = keyImporter ?: run {
            _sessionUi.value = DeviceSessionUi(
                statusMessage = "Connected. Import device identity keys to sync.",
            )
            return
        }
        if (!importer.hasKeys()) {
            _sessionUi.value = DeviceSessionUi(
                statusMessage = "Connected. Import device identity keys to sync.",
            )
            return
        }
        val keys = importer.loadKeys() ?: run {
            _sessionUi.value = DeviceSessionUi(
                statusMessage = "Connected. Identity keys incomplete.",
            )
            return
        }
        _sessionUi.value = DeviceSessionUi(statusMessage = "Unlocking…")
        try {
            val c = PlaudDeviceClient(ble, createSessionCipher(), keys)
            client = c
            c.unlock()
            val hs = c.handshake()
            val sessions = c.listSessions()
            val battery = c.readBattery()
            _sessionUi.value = DeviceSessionUi(
                batteryPercent = battery.percent,
                sessions = sessions.entries,
                protocolVersion = hs.protocolVersion,
                statusMessage = "Ready · protocol ${hs.protocolVersion}",
            )
        } catch (e: Exception) {
            _sessionUi.value = DeviceSessionUi(
                statusMessage = "Protocol error: ${e.message ?: e::class.simpleName}",
            )
        }
    }
}
