package dev.velum.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.velum.ble.BleConnectionState
import dev.velum.ble.BleTransport
import dev.velum.ble.client.PlaudDeviceClient
import dev.velum.ble.crypto.createSessionCipher
import dev.velum.core.model.ConnectedDevice
import dev.velum.core.security.DeviceKeyImporter
import dev.velum.protocol.DeviceCapabilities
import dev.velum.protocol.DeviceCapabilityRegistry
import dev.velum.protocol.SessionEntry
import dev.velum.recorder.RecorderDriverRegistry
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
    val modelDisplayName: String? = null,
    val wifiFastPath: Boolean = false,
    val capabilities: DeviceCapabilities? = null,
    val recorderDriverId: String? = null,
)

class DeviceViewModel(
    private val ble: BleTransport,
    private val keyImporter: DeviceKeyImporter? = null,
    /** When false, SoftAP Wi‑Fi banner stays off even if the device supports it. */
    private val preferWifiFastPath: () -> Boolean = { true },
) : ViewModel() {
    val connectionState: StateFlow<BleConnectionState> =
        ble.connectionState.stateIn(viewModelScope, SharingStarted.Eagerly, BleConnectionState.Idle)

    val devices: StateFlow<List<ConnectedDevice>> =
        ble.discoveredDevices.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _sessionUi = MutableStateFlow(DeviceSessionUi())
    val sessionUi: StateFlow<DeviceSessionUi> = _sessionUi.asStateFlow()

    private var client: PlaudDeviceClient? = null

    init {
        viewModelScope.launch {
            connectionState.collect { state ->
                when (state) {
                    is BleConnectionState.Connected,
                    is BleConnectionState.Connecting,
                    -> Unit
                    BleConnectionState.Idle,
                    BleConnectionState.Scanning,
                    is BleConnectionState.Failed,
                    -> closeClient()
                }
            }
        }
    }

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
            closeClient()
            _sessionUi.value = DeviceSessionUi()
            ble.disconnect()
        }
    }

    private fun closeClient() {
        client?.close()
        client = null
    }

    private suspend fun runProtocolIfKeysPresent() {
        val connected = (connectionState.value as? BleConnectionState.Connected)?.device
        val scanMatch = RecorderDriverRegistry.matchAdvertisement(
            name = connected?.name,
            manufacturerPayload = null,
        )
        val driverId = scanMatch?.driver?.id
        if (driverId != null && driverId != "plaud") {
            _sessionUi.value = DeviceSessionUi(
                modelDisplayName = scanMatch.capabilities.productLabel,
                recorderDriverId = driverId,
                statusMessage = "Connected · ${scanMatch.capabilities.productLabel} " +
                    "(${scanMatch.driver.displayName}). Live/file sync for this driver " +
                    "is not wired on-device yet — protocol unit-tested only.",
            )
            return
        }
        val importer = keyImporter ?: run {
            _sessionUi.value = DeviceSessionUi(
                recorderDriverId = "plaud",
                statusMessage = "Connected. Import device identity keys to sync.",
            )
            return
        }
        if (!importer.hasKeys()) {
            _sessionUi.value = DeviceSessionUi(
                recorderDriverId = "plaud",
                statusMessage = "Connected. Import device identity keys to sync.",
            )
            return
        }
        val keys = importer.loadKeys() ?: run {
            _sessionUi.value = DeviceSessionUi(
                recorderDriverId = "plaud",
                statusMessage = "Connected. Identity keys incomplete.",
            )
            return
        }
        _sessionUi.value = DeviceSessionUi(statusMessage = "Unlocking…", recorderDriverId = "plaud")
        try {
            val capsBase = DeviceCapabilityRegistry.detect(
                productCode = connected?.modelCode,
                advertisedName = connected?.name,
            )
            val c = PlaudDeviceClient(ble, createSessionCipher(), keys)
            client = c
            c.unlock()
            val hs = c.handshake()
            val caps = capsBase.withHandshake(hs)
            val sessions = c.listSessions()
            val battery = c.readBattery()
            val wifiCapable = DeviceCapabilityRegistry.supportsWifiFastPath(caps)
            val wifi = wifiCapable && preferWifiFastPath()
            _sessionUi.value = DeviceSessionUi(
                batteryPercent = battery.percent,
                sessions = sessions.entries,
                protocolVersion = hs.protocolVersion,
                modelDisplayName = caps.displayName,
                wifiFastPath = wifi,
                capabilities = caps,
                recorderDriverId = "plaud",
                statusMessage = buildString {
                    append("Ready · ${caps.displayName}")
                    append(" · protocol ${hs.protocolVersion}")
                    when {
                        wifi -> append(" · Wi‑Fi fast path")
                        wifiCapable -> append(" · BLE transfer (Wi‑Fi preferred off)")
                        else -> append(" · BLE transfer")
                    }
                },
            )
        } catch (e: Exception) {
            _sessionUi.value = DeviceSessionUi(
                recorderDriverId = "plaud",
                statusMessage = "Protocol error: ${e.message ?: e::class.simpleName}",
            )
        }
    }
}
