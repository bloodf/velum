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
import dev.velum.ble.client.createTempSessionFile
import dev.velum.ble.crypto.createFileDecryptor
import dev.velum.ble.crypto.createSessionCipher
import dev.velum.core.model.ConnectedDevice
import dev.velum.core.security.DeviceKeyImporter
import dev.velum.core.security.SecureStoreUnavailableException
import dev.velum.protocol.DeviceCapabilities
import dev.velum.protocol.DeviceCapabilityRegistry
import dev.velum.protocol.PlaudFileCrypto
import dev.velum.protocol.SessionEntry
import dev.velum.recorder.RecorderDriverRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
    /** Device sessions already in the library (#113); their action re-downloads into the same row. */
    val librarySessionIds: Set<Long> = emptySet(),
)

/** Real device serial for the #113 dedup key, or null (dedup off) when none is known. */
internal fun dedupDeviceSerial(keySerial: String?, advertisedSerial: String?): String? =
    keySerial?.takeIf { it.isNotBlank() } ?: advertisedSerial?.takeIf { it.isNotBlank() }

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
    @Suppress("unused")
    private val preferWifiFastPath: () -> Boolean = { true },
    private val persistDownloadedSession: suspend (DownloadedSessionAudio) -> String = {
        error("persistDownloadedSession not configured")
    },
    private val localeTag: () -> String = { systemLanguageTag() },
    private val librarySessionIds: suspend (sessionIds: List<Long>, deviceSerial: String?) -> Set<Long> = { _, _ -> emptySet() },
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
    private val downloadMutex = Mutex()

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
            try {
                ble.connect(deviceId)
                runProtocolIfKeysPresent()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SecureStoreUnavailableException) {
                _sessionUi.value = DeviceSessionUi(statusMessage = DeviceUiCopy.secureStorageUnavailable(localeTag()))
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            cancelDownload()
            downloadJob?.join()
            closeClient()
            _sessionUi.value = DeviceSessionUi()
            ble.disconnect()
        }
    }

    fun cancelDownload() {
        val locale = localeTag()
        downloadJob?.cancel()
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
        if (downloadJob?.isActive == true && _sessionUi.value.downloadingSessionId != null) return
        val previous = downloadJob
        val started = viewModelScope.launch {
            previous?.join()
            try {
                downloadMutex.withLock {
                    runDownloadSession(sessionId)
                }
            } catch (_: SecureStoreUnavailableException) {
                _sessionUi.value = _sessionUi.value.copy(
                    downloadingSessionId = null,
                    downloadProgress = null,
                    statusMessage = DeviceUiCopy.secureStorageUnavailable(localeTag()),
                )
            } finally {
                DownloadProgressNotifier.dismiss(DownloadNotifyKind.DeviceSession)
                DownloadProgressNotifier.setCancelHandler(DownloadNotifyKind.DeviceSession, null)
            }
        }
        downloadJob = started
        started.invokeOnCompletion {
            if (downloadJob === started) downloadJob = null
        }
    }

    private suspend fun runDownloadSession(sessionId: Long) {
        val locale = localeTag()
        val entry = _sessionUi.value.sessions.firstOrNull { it.sessionId == sessionId }
        if (entry == null) {
            _sessionUi.value = _sessionUi.value.copy(statusMessage = DeviceUiCopy.sessionNotFound(locale))
            return
        }
        val c = client
        if (c == null) {
            _sessionUi.value = _sessionUi.value.copy(statusMessage = DeviceUiCopy.notConnected(locale))
            return
        }
        val keys = keyImporter?.loadKeys()
        if (keys == null) {
            _sessionUi.value = _sessionUi.value.copy(statusMessage = DeviceUiCopy.importKeysToDownload(locale))
            return
        }
        val totalBytes = entry.sizeBytes.coerceAtLeast(1L)
        _sessionUi.value = _sessionUi.value.copy(
            downloadingSessionId = sessionId,
            downloadProgress = 0f,
            statusMessage = DeviceUiCopy.downloadingSession(locale, sessionId),
            lastDownloadedRecordingId = null,
        )
        DownloadProgressNotifier.ensureChannel()
        DownloadProgressNotifier.setCancelHandler(DownloadNotifyKind.DeviceSession) { cancelDownload() }
        DownloadProgressNotifier.show(
            kind = DownloadNotifyKind.DeviceSession,
            title = DeviceUiCopy.downloadNotificationTitle(locale),
            body = DeviceUiCopy.downloadingSession(locale, sessionId),
            progress = 0f,
            cancelLabel = DeviceUiCopy.cancel(locale),
        )
        try {
            val recordingId = withContext(Dispatchers.IO) {
                assembleDecryptAndPersist(
                    client = c,
                    sessionId = sessionId,
                    sizeBytes = entry.sizeBytes,
                    totalBytes = totalBytes,
                    privateKeyPem = keys.privateKeyPem,
                    deviceSerial = dedupDeviceSerial(keys.serialNumber, connectedSerial),
                    startedAtEpochSeconds = sessionId.coerceAtLeast(0L),
                )
            }
            val sessions = c.listSessions()
            val inLibrary = loadLibrarySessionIds(sessions.entries.map { it.sessionId }, dedupDeviceSerial(keys.serialNumber, connectedSerial))
            _sessionUi.value = _sessionUi.value.copy(
                sessions = sessions.entries,
                librarySessionIds = inLibrary,
                downloadingSessionId = null,
                downloadProgress = null,
                lastDownloadedRecordingId = recordingId,
                statusMessage = DeviceUiCopy.downloadedSession(localeTag(), sessionId),
            )
        } catch (e: CancellationException) {
            _sessionUi.value = _sessionUi.value.copy(
                downloadingSessionId = null,
                downloadProgress = null,
                statusMessage = DeviceUiCopy.downloadCancelled(localeTag()),
            )
            throw e
        } catch (_: Exception) {
            _sessionUi.value = _sessionUi.value.copy(
                downloadingSessionId = null,
                downloadProgress = null,
                statusMessage = DeviceUiCopy.downloadFailed(localeTag()),
            )
        }
    }

    private suspend fun assembleDecryptAndPersist(
        client: PlaudDeviceClient,
        sessionId: Long,
        sizeBytes: Long,
        totalBytes: Long,
        privateKeyPem: String,
        deviceSerial: String?,
        startedAtEpochSeconds: Long,
    ): String {
        fun onProgress(received: Long) {
            val progress = (received.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 0.99f)
            _sessionUi.value = _sessionUi.value.copy(downloadProgress = progress)
            DownloadProgressNotifier.update(DownloadNotifyKind.DeviceSession, progress)
        }
        val temp = createTempSessionFile()
        val encrypted = if (temp != null) {
            try {
                client.downloadSessionTo(sessionId, sizeBytes, temp, ::onProgress)
                temp.readAll()
            } finally {
                temp.delete()
            }
        } else {
            client.downloadSession(sessionId, sizeBytes, ::onProgress)
        }
        val decrypted = PlaudFileCrypto.decrypt(encrypted, privateKeyPem, createFileDecryptor())
        return persistDownloadedSession(
            DownloadedSessionAudio(
                sessionId = sessionId,
                deviceSerial = deviceSerial,
                startedAtEpochSeconds = startedAtEpochSeconds,
                sizeBytes = decrypted.size.toLong(),
                decryptedBytes = decrypted,
            ),
        )
    }

    /** Library marking is best-effort: a lookup failure must not break the session list. */
    private suspend fun loadLibrarySessionIds(ids: List<Long>, serial: String?): Set<Long> =
        try {
            librarySessionIds(ids, serial)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptySet()
        }

    private fun closeClient() {
        client?.close()
        client = null
        connectedSerial = null
    }

    private suspend fun runProtocolIfKeysPresent() {
        val locale = localeTag()
        val connected = (connectionState.value as? BleConnectionState.Connected)?.device
        connectedSerial = connected?.serial // never the BLE name: it is not a stable device identity (#113)
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
            val inLibrary = loadLibrarySessionIds(sessions.entries.map { it.sessionId }, dedupDeviceSerial(keys.serialNumber, connectedSerial))
            _sessionUi.value = DeviceSessionUi(
                batteryPercent = battery.percent,
                sessions = sessions.entries,
                librarySessionIds = inLibrary,
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
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            _sessionUi.value = DeviceSessionUi(
                recorderDriverId = "plaud",
                statusMessage = DeviceUiCopy.unlockFailed(localeTag()),
            )
        }
    }
}
