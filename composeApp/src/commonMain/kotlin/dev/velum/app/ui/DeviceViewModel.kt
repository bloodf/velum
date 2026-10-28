package dev.velum.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.velum.app.download.SessionDownloadManager
import dev.velum.app.download.SessionDownloadRequest
import dev.velum.app.i18n.DeviceUiCopy
import dev.velum.app.platform.systemLanguageTag
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
    val downloadingSessionId: Long? = null,
    val downloadProgress: Float? = null,
    val lastDownloadedRecordingId: String? = null,
)

data class DownloadedSessionAudio(
    val sessionId: Long,
    val deviceSerial: String?,
    val startedAtEpochSeconds: Long,
    val sizeBytes: Long,
    val decryptedBytes: ByteArray,
)

/**
 * Device screen state. Session downloads are owned by [SessionDownloadManager]
 * (app scope + FGS); this ViewModel only starts/cancels and mirrors [SessionDownloadManager.state].
 */
class DeviceViewModel(
    private val ble: BleTransport,
    private val keyImporter: DeviceKeyImporter? = null,
    @Suppress("unused")
    private val preferWifiFastPath: () -> Boolean = { true },
    private val sessionDownloads: SessionDownloadManager,
    private val localeTag: () -> String = { systemLanguageTag() },
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
        viewModelScope.launch {
            sessionDownloads.state.collect { dl ->
                _sessionUi.value = _sessionUi.value.copy(
                    downloadingSessionId = dl.downloadingSessionId,
                    downloadProgress = dl.downloadProgress,
                    statusMessage = dl.statusMessage ?: _sessionUi.value.statusMessage,
                    lastDownloadedRecordingId = dl.lastDownloadedRecordingId
                        ?: _sessionUi.value.lastDownloadedRecordingId,
                    sessions = dl.sessionsUpdate ?: _sessionUi.value.sessions,
                )
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
            cancelDownload()
            sessionDownloads.join()
            closeClient()
            _sessionUi.value = DeviceSessionUi()
            ble.disconnect()
        }
    }

    fun cancelDownload() {
        sessionDownloads.cancel()
    }

    fun downloadSession(sessionId: Long) {
        if (sessionDownloads.isDownloadActive && _sessionUi.value.downloadingSessionId != null) return
        // Only resolve keys / client here; the download job itself runs on SessionDownloadManager.
        viewModelScope.launch {
            val locale = localeTag()
            val entry = _sessionUi.value.sessions.firstOrNull { it.sessionId == sessionId }
            if (entry == null) {
                _sessionUi.value = _sessionUi.value.copy(statusMessage = DeviceUiCopy.sessionNotFound(locale))
                return@launch
            }
            val c = client
            if (c == null) {
                _sessionUi.value = _sessionUi.value.copy(statusMessage = DeviceUiCopy.notConnected(locale))
                return@launch
            }
            val keys = keyImporter?.loadKeys()
            if (keys == null) {
                _sessionUi.value = _sessionUi.value.copy(statusMessage = DeviceUiCopy.importKeysToDownload(locale))
                return@launch
            }
            sessionDownloads.start(
                SessionDownloadRequest(
                    sessionId = sessionId,
                    sizeBytes = entry.sizeBytes,
                    privateKeyPem = keys.privateKeyPem,
                    deviceSerial = keys.serialNumber.ifBlank { connectedSerial },
                    startedAtEpochSeconds = sessionId.coerceAtLeast(0L),
                    client = c,
                ),
            )
        }
    }

    private fun closeClient() {
        client?.close()
        client = null
        connectedSerial = null
    }

    private suspend fun runProtocolIfKeysPresent() {
        val locale = localeTag()
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
                statusMessage = DeviceUiCopy.unsupportedRecorder(locale),
            )
            return
        }
        val importer = keyImporter ?: run {
            _sessionUi.value = DeviceSessionUi(
                recorderDriverId = "plaud",
                statusMessage = DeviceUiCopy.connectedImportKeys(locale),
            )
            return
        }
        if (!importer.hasKeys()) {
            _sessionUi.value = DeviceSessionUi(
                recorderDriverId = "plaud",
                statusMessage = DeviceUiCopy.connectedImportKeys(locale),
            )
            return
        }
        val keys = importer.loadKeys() ?: run {
            _sessionUi.value = DeviceSessionUi(
                recorderDriverId = "plaud",
                statusMessage = DeviceUiCopy.connectedKeysIncomplete(locale),
            )
            return
        }
        _sessionUi.value = DeviceSessionUi(
            statusMessage = DeviceUiCopy.unlocking(locale),
            recorderDriverId = "plaud",
        )
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
            _sessionUi.value = DeviceSessionUi(
                batteryPercent = battery.percent,
                sessions = sessions.entries,
                protocolVersion = hs.protocolVersion,
                modelDisplayName = caps.displayName,
                wifiFastPath = false,
                capabilities = caps,
                recorderDriverId = "plaud",
                statusMessage = DeviceUiCopy.readyStatus(
                    localeTag = localeTag(),
                    displayName = caps.displayName,
                    protocolVersion = hs.protocolVersion,
                    wifiPreferred = false,
                    wifiCapable = wifiCapable,
                ),
            )
        } catch (_: Exception) {
            _sessionUi.value = DeviceSessionUi(
                recorderDriverId = "plaud",
                statusMessage = DeviceUiCopy.unlockFailed(localeTag()),
            )
        }
    }
}
