package dev.velum.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.velum.app.i18n.DeviceUiCopy
import dev.velum.app.platform.DownloadNotifyKind
import dev.velum.app.platform.DownloadProgressNotifier
import dev.velum.app.platform.systemLanguageTag
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
    /** 0f..1f while a session download is in flight; null when idle. */
    val downloadProgress: Float? = null,
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
    private var downloadJob: Job? = null

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
            cancelDownload()
            closeClient()
            _sessionUi.value = DeviceSessionUi()
            ble.disconnect()
        }
    }

    fun cancelDownload() {
        val locale = localeTag()
        downloadJob?.cancel()
        downloadJob = null
        DownloadProgressNotifier.dismiss(DownloadNotifyKind.DeviceSession)
        DownloadProgressNotifier.setCancelHandler(DownloadNotifyKind.DeviceSession, null)
        if (_sessionUi.value.downloadingSessionId != null) {
            _sessionUi.value = _sessionUi.value.copy(
                downloadingSessionId = null,
                downloadProgress = null,
                statusMessage = DeviceUiCopy.downloadCancelled(locale),
            )
        }
    }

    fun downloadSession(sessionId: Long) {
        if (downloadJob?.isActive == true) return
        downloadJob = viewModelScope.launch {
            val locale = localeTag()
            val entry = _sessionUi.value.sessions.firstOrNull { it.sessionId == sessionId }
            if (entry == null) {
                _sessionUi.value = _sessionUi.value.copy(
                    statusMessage = DeviceUiCopy.sessionNotFound(locale),
                )
                return@launch
            }
            val c = client
            if (c == null) {
                _sessionUi.value = _sessionUi.value.copy(
                    statusMessage = DeviceUiCopy.notConnected(locale),
                )
                return@launch
            }
            val keys = keyImporter?.loadKeys()
            if (keys == null) {
                _sessionUi.value = _sessionUi.value.copy(
                    statusMessage = DeviceUiCopy.importKeysToDownload(locale),
                )
                return@launch
            }
            val totalBytes = entry.sizeBytes.coerceAtLeast(1L)
            _sessionUi.value = _sessionUi.value.copy(
                downloadingSessionId = sessionId,
                downloadProgress = 0f,
                statusMessage = DeviceUiCopy.downloadingSession(locale, sessionId),
                lastDownloadedRecordingId = null,
            )
            DownloadProgressNotifier.ensureChannel()
            DownloadProgressNotifier.setCancelHandler(DownloadNotifyKind.DeviceSession) {
                cancelDownload()
            }
            DownloadProgressNotifier.show(
                kind = DownloadNotifyKind.DeviceSession,
                title = DeviceUiCopy.downloadNotificationTitle(locale),
                body = DeviceUiCopy.downloadingSession(locale, sessionId),
                progress = 0f,
                cancelLabel = DeviceUiCopy.cancel(locale),
            )
            try {
                // BLE path is the reliable product path for 1.0. SoftAP prefer-Wi‑Fi
                // remains available in the protocol layer; credentials on hardware are
                // confirmed separately (see docs/HARDWARE_VALIDATION.md).
                val encrypted = c.downloadSession(sessionId, entry.sizeBytes) { received ->
                    val progress = (received.toDouble() / totalBytes.toDouble())
                        .toFloat()
                        .coerceIn(0f, 0.99f)
                    _sessionUi.value = _sessionUi.value.copy(downloadProgress = progress)
                    DownloadProgressNotifier.update(DownloadNotifyKind.DeviceSession, progress)
                }
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
                    downloadProgress = null,
                    lastDownloadedRecordingId = recordingId,
                    statusMessage = DeviceUiCopy.downloadedSession(localeTag(), sessionId),
                )
            } catch (_: CancellationException) {
                _sessionUi.value = _sessionUi.value.copy(
                    downloadingSessionId = null,
                    downloadProgress = null,
                    statusMessage = DeviceUiCopy.downloadCancelled(localeTag()),
                )
            } catch (_: Exception) {
                _sessionUi.value = _sessionUi.value.copy(
                    downloadingSessionId = null,
                    downloadProgress = null,
                    statusMessage = DeviceUiCopy.downloadFailed(localeTag()),
                )
            } finally {
                downloadJob = null
                DownloadProgressNotifier.dismiss(DownloadNotifyKind.DeviceSession)
                DownloadProgressNotifier.setCancelHandler(DownloadNotifyKind.DeviceSession, null)
            }
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
            val wifi = wifiCapable && preferWifiFastPath()
            _sessionUi.value = DeviceSessionUi(
                batteryPercent = battery.percent,
                sessions = sessions.entries,
                protocolVersion = hs.protocolVersion,
                modelDisplayName = caps.displayName,
                wifiFastPath = wifi,
                capabilities = caps,
                recorderDriverId = "plaud",
                statusMessage = DeviceUiCopy.readyStatus(
                    localeTag = localeTag(),
                    displayName = caps.displayName,
                    protocolVersion = hs.protocolVersion,
                    wifiPreferred = wifi,
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
