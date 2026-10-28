package dev.velum.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.velum.ble.BleConnectionState
import dev.velum.ble.BleTransport
import dev.velum.ble.client.PlaudDeviceClient
import dev.velum.ble.crypto.createFileDecryptor
import dev.velum.ble.crypto.createSessionCipher
import dev.velum.core.model.ConnectedDevice
import dev.velum.core.security.DeviceKeyImporter
import dev.velum.protocol.DeviceCapabilities
import dev.velum.protocol.DeviceCapabilityRegistry
import dev.velum.protocol.PlaudFileCrypto
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
    val downloadingSessionId: Long? = null,
    val lastDownloadedRecordingId: String? = null,
)

/**
 * Decrypted session payload ready for the library.
 */
data class DownloadedSessionAudio(
    val sessionId: Long,
    val deviceSerial: String?,
    val startedAtEpochSeconds: Long,
    val sizeBytes: Long,
    val decryptedBytes: ByteArray,
)

class DeviceViewModel(
    private val ble: BleTransport,
    private val keyImporter: DeviceKeyImporter? = null,
    /** When false, SoftAP Wi‑Fi banner stays off even if the device supports it. */
    private val preferWifiFastPath: () -> Boolean = { true },
    /**
     * Persist decrypted audio into the library and return the new recording id.
     * Injected from [dev.velum.app.di.AppContainer].
     */
    private val persistDownloadedSession: suspend (DownloadedSessionAudio) -> String = {
        error("persistDownloadedSession not configured")
    },
) : ViewModel() {
    val connectionState: StateFlow<BleConnectionState> =
        ble.connectionState.stateIn(viewModelScope, SharingStarted.Eagerly, BleConnectionState.Idle)

    val devices: StateFlow<List<ConnectedDevice>> =
        ble.discoveredDevices.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _sessionUi = MutableStateFlow(DeviceSessionUi())
    val sessionUi: StateFlow<DeviceSessionUi> = _sessionUi.asStateFlow()

    private var client: PlaudDeviceClient? = null
    private var connectedSerial: String? = null

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

    fun downloadSession(sessionId: Long) {
        viewModelScope.launch {
            val entry = _sessionUi.value.sessions.firstOrNull { it.sessionId == sessionId }
            if (entry == null) {
                _sessionUi.value = _sessionUi.value.copy(
                    statusMessage = "Session not found on device.",
                )
                return@launch
            }
            val c = client
            if (c == null) {
                _sessionUi.value = _sessionUi.value.copy(
                    statusMessage = "Not connected. Connect and unlock first.",
                )
                return@launch
            }
            val importer = keyImporter
            val keys = importer?.loadKeys()
            if (keys == null) {
                _sessionUi.value = _sessionUi.value.copy(
                    statusMessage = "Import device identity keys to download.",
                )
                return@launch
            }
            _sessionUi.value = _sessionUi.value.copy(
                downloadingSessionId = sessionId,
                statusMessage = "Downloading session #$sessionId…",
                lastDownloadedRecordingId = null,
            )
            try {
                // BLE path is the reliable product path for 1.0. SoftAP prefer-Wi‑Fi
                // remains available in the protocol layer; credentials on hardware are
                // confirmed separately (see docs/HARDWARE_VALIDATION.md).
                val encrypted = c.downloadSession(sessionId, entry.sizeBytes)
                val decrypted = PlaudFileCrypto.decrypt(
                    encrypted,
                    keys.privateKeyPem,
                    createFileDecryptor(),
                )
                val recordingId = persistDownloadedSession(
                    DownloadedSessionAudio(
                        sessionId = sessionId,
                        deviceSerial = keys.serialNumber.ifBlank { connectedSerial },
                        startedAtEpochSeconds = sessionId.coerceAtLeast(0L),
                        sizeBytes = decrypted.size.toLong(),
                        decryptedBytes = decrypted,
                    ),
                )
                val sessions = c.listSessions()
                _sessionUi.value = _sessionUi.value.copy(
                    sessions = sessions.entries,
                    downloadingSessionId = null,
                    lastDownloadedRecordingId = recordingId,
                    statusMessage = "Downloaded session #$sessionId",
                )
            } catch (e: Exception) {
                _sessionUi.value = _sessionUi.value.copy(
                    downloadingSessionId = null,
                    statusMessage = "Download failed. Check the connection and try again.",
                )
            }
        }
    }

    private fun closeClient() {
        client?.close()
        client = null
        connectedSerial = null
    }

    private suspend fun runProtocolIfKeysPresent() {
        val connected = (connectionState.value as? BleConnectionState.Connected)?.device
        connectedSerial = connected?.serial ?: connected?.name
        val scanMatch = RecorderDriverRegistry.matchAdvertisement(
            name = connected?.name,
            manufacturerPayload = null,
        )
        val driverId = scanMatch?.driver?.id
        if (driverId != null && driverId != "plaud") {
            _sessionUi.value = DeviceSessionUi(
                modelDisplayName = scanMatch.capabilities.productLabel,
                recorderDriverId = driverId,
                statusMessage = "This recorder model is not supported in this version.",
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
                        wifi -> append(" · Wi‑Fi preferred (BLE download active)")
                        wifiCapable -> append(" · BLE transfer (Wi‑Fi preferred off)")
                        else -> append(" · BLE transfer")
                    }
                },
            )
        } catch (_: Exception) {
            _sessionUi.value = DeviceSessionUi(
                recorderDriverId = "plaud",
                statusMessage = "Could not unlock the device. Check keys and try again.",
            )
        }
    }
}
