package dev.velum.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.velum.app.i18n.DeviceUiCopy
import dev.velum.app.platform.DownloadNotifyKind
import dev.velum.app.platform.DownloadProgressNotifier
import dev.velum.app.platform.PartialTransfer
import dev.velum.app.platform.deleteAllPartialTransfers
import dev.velum.app.platform.openPartialTransfer as defaultOpenPartialTransfer
import dev.velum.app.platform.systemLanguageTag
import dev.velum.ble.BleConnectionState
import dev.velum.ble.BleTransport
import dev.velum.ble.client.CrcPolicy
import dev.velum.ble.client.CrcStatus
import dev.velum.ble.client.PlaudDeviceClient
import dev.velum.ble.client.ResumableSessionDownload
import dev.velum.ble.crypto.createFileDecryptor
import dev.velum.ble.crypto.createSessionCipher
import dev.velum.core.model.ConnectedDevice
import dev.velum.core.model.ProcessingWarning
import dev.velum.core.security.DeviceKeyImporter
import dev.velum.core.security.SecureStoreUnavailableException
import dev.velum.protocol.DeviceCapabilities
import dev.velum.protocol.DeviceCapabilityRegistry
import dev.velum.protocol.DeviceIdentityKeys
import dev.velum.protocol.PlaudFileCrypto
import dev.velum.protocol.SessionEntry
import dev.velum.recorder.RecorderDriverRegistry
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
    /** CRC result of the last download's final transfer; null until one completes. A resumed save can report a kept mismatch in its status message instead. */
    val lastDownloadCrc: CrcStatus? = null,
    /** Device sessions already in the library (#113); their action re-downloads into the same row. */
    val librarySessionIds: Set<Long> = emptySet(),
)

/** Real device serial for the #113 dedup key, or null (dedup off) when none is known. */
internal fun dedupDeviceSerial(keySerial: String?, advertisedSerial: String?): String? =
    keySerial?.takeIf { it.isNotBlank() } ?: advertisedSerial?.takeIf { it.isNotBlank() }

/** A complete partial that cannot be read back this many times in a row is discarded and downloaded again (#212). */
internal const val MAX_READ_FAILURES = 3

data class DownloadedSessionAudio(
    val sessionId: Long,
    val deviceSerial: String?,
    val startedAtEpochSeconds: Long,
    /** A [dev.velum.core.model.ProcessingWarning] code stored on the recording, or null. Before the lambda so callers can pass it trailing. */
    val processingWarning: String? = null,
    /**
     * Streams decrypted audio into the sink in bounded chunks (never the whole file at
     * once) and returns the total byte count. Throws on decrypt failure or cancellation.
     */
    val writeDecryptedAudio: (sink: (ByteArray) -> Unit) -> Long,
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
    private val openPartialTransfer: (sessionId: Long, deviceSerial: String?) -> PartialTransfer? = ::defaultOpenPartialTransfer,
    private val deletePartialTransfers: () -> Unit = ::deleteAllPartialTransfers,
    private val wipeJoinTimeout: Duration = 15.seconds,
) : ViewModel() {
    val connectionState: StateFlow<BleConnectionState> =
        ble.connectionState.stateIn(viewModelScope, SharingStarted.Eagerly, BleConnectionState.Idle)

    val devices: StateFlow<List<ConnectedDevice>> =
        ble.discoveredDevices.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _sessionUi = MutableStateFlow(DeviceSessionUi())
    val sessionUi: StateFlow<DeviceSessionUi> = _sessionUi.asStateFlow()

    private var client: PlaudDeviceClient? = null
    private var connectedSerial: String? = null

    /** Set when a download attempt discarded its partial after repeated read errors; picks the failure text. */
    private var discardedUnreadablePartial = false
    private var connectedDeviceId: String? = null
    private var downloadJob: Job? = null
    private var cancelledJob: Job? = null
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
        // The link and its client belong to the running download; a second connect would replace them mid-transfer.
        // Same condition as downloadSession: a queued job that has not started yet owns nothing.
        if (downloadJob?.isActive == true && _sessionUi.value.downloadingSessionId != null) return
        viewModelScope.launch {
            // A cancelled download may still be unwinding (a BLE write can ignore cancellation) and a queued one may
            // start right behind it; both use the client, so connect only after them, with the same bound as a wipe.
            val settled = withTimeoutOrNull(wipeJoinTimeout) {
                cancelledJob?.join()
                downloadJob?.join()
                true
            }
            if (settled == null) {
                _sessionUi.value = _sessionUi.value.copy(statusMessage = DeviceUiCopy.connectBusy(localeTag()))
                return@launch
            }
            try {
                connectedDeviceId = deviceId
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
            connectedSerial = null // an explicit disconnect forgets the device; a link drop does not
            _sessionUi.value = DeviceSessionUi()
            ble.disconnect()
        }
    }

    fun cancelDownload() {
        val locale = localeTag()
        downloadJob?.let { it.cancel(); cancelledJob = it }
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

    /**
     * For "Wipe all data": stops any download first, so it can't write or save anything afterwards. The wait
     * is bounded because a BLE write may ignore cancellation. Returns false if a partial could not be deleted.
     */
    suspend fun wipePartialDownloads(): Boolean {
        cancelDownload()
        withTimeoutOrNull(wipeJoinTimeout) { downloadJob?.join() }
        return try {
            withContext(Dispatchers.IO) { deletePartialTransfers() }
            true
        } catch (_: Exception) {
            false
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
        discardedUnreadablePartial = false
        val locale = localeTag()
        val entry = _sessionUi.value.sessions.firstOrNull { it.sessionId == sessionId }
        if (entry == null) {
            _sessionUi.value = _sessionUi.value.copy(statusMessage = DeviceUiCopy.sessionNotFound(locale))
            return
        }
        // No client is fine while the link is up (an earlier attempt closed it): the download reconnects.
        val c = client
        if (c == null && connectionState.value !is BleConnectionState.Connected) {
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
            lastDownloadCrc = null,
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
        val serial = dedupDeviceSerial(keys.serialNumber, connectedSerial)
        try {
            val outcome = withContext(Dispatchers.IO) {
                assembleDecryptAndPersist(
                    initialClient = c,
                    keys = keys,
                    sessionId = sessionId,
                    sizeBytes = entry.sizeBytes,
                    totalBytes = totalBytes,
                    deviceSerial = serial,
                    startedAtEpochSeconds = sessionId.coerceAtLeast(0L),
                )
            }
            adoptClient(outcome.client)
            connectedSerial = connectedSerial ?: serial
            // The recording is saved: a failed refresh must not report the download as failed.
            val sessions = try { outcome.client?.listSessions()?.entries } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
                ?: _sessionUi.value.sessions
            val inLibrary = loadLibrarySessionIds(sessions.map { it.sessionId }, serial)
            _sessionUi.value = _sessionUi.value.copy(
                sessions = sessions,
                librarySessionIds = inLibrary,
                downloadingSessionId = null,
                downloadProgress = null,
                lastDownloadedRecordingId = outcome.recordingId,
                lastDownloadCrc = outcome.crc,
                statusMessage = if (outcome.crcMismatch) {
                    DeviceUiCopy.downloadedSessionCrcMismatch(localeTag(), sessionId)
                } else {
                    DeviceUiCopy.downloadedSession(localeTag(), sessionId)
                },
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
                statusMessage = if (discardedUnreadablePartial) {
                    DeviceUiCopy.savedDownloadUnreadable(localeTag())
                } else {
                    DeviceUiCopy.downloadFailed(localeTag())
                },
            )
        }
    }

    private suspend fun assembleDecryptAndPersist(
        initialClient: PlaudDeviceClient?,
        keys: DeviceIdentityKeys,
        sessionId: Long,
        sizeBytes: Long,
        totalBytes: Long,
        deviceSerial: String?,
        startedAtEpochSeconds: Long,
    ): DownloadOutcome {
        fun onProgress(received: Long) {
            val progress = (received.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 0.99f)
            _sessionUi.value = _sessionUi.value.copy(downloadProgress = progress)
            DownloadProgressNotifier.update(DownloadNotifyKind.DeviceSession, progress)
        }
        val job = currentCoroutineContext()[Job]
        val decryptor = createFileDecryptor()
        var saveFailed = false
        var readFailed = false
        var undecryptable = false
        var crcMismatch = false // set from the partial's mark, then from this attempt's result
        val deviceId = connectedDeviceId
        fun persist(size: Long, readAt: (Long, Int) -> ByteArray) = DownloadedSessionAudio(
            sessionId = sessionId,
            deviceSerial = deviceSerial,
            startedAtEpochSeconds = startedAtEpochSeconds,
            processingWarning = if (crcMismatch) ProcessingWarning.DEVICE_CRC_MISMATCH else null,
            writeDecryptedAudio = { sink ->
                try {
                    val checkedRead = { position: Long, length: Int ->
                        try {
                            readAt(position, length)
                        } catch (e: Exception) {
                            readFailed = true
                            throw e
                        }
                    }
                    PlaudFileCrypto.decryptStream(size, checkedRead, keys.privateKeyPem, decryptor) {
                        job?.ensureActive()
                        try {
                            sink(it)
                        } catch (e: Exception) {
                            saveFailed = true
                            throw e
                        }
                    }
                } catch (e: Exception) {
                    if (!saveFailed && !readFailed && e !is CancellationException) undecryptable = true
                    throw e
                }
            },
        )
        val partial = openPartialTransfer(sessionId, deviceSerial)
        // A mismatch seen on an earlier attempt that then failed to save is remembered with the partial.
        crcMismatch = partial?.crcMismatch() == true
        if (partial == null) {
            val c = initialClient ?: throw IllegalStateException("Not connected")
            val encrypted = c.downloadSession(sessionId, sizeBytes, ::onProgress)
            val recordingId = persistDownloadedSession(
                persist(encrypted.size.toLong()) { position, length ->
                    encrypted.copyOfRange(position.toInt(), position.toInt() + length)
                },
            )
            return DownloadOutcome(recordingId, c, crcMismatch = false, crc = CrcStatus.NotProvided)
        }
        // A transfer failure or cancellation keeps the partial for the next attempt.
        client = null // the download owns it now: it may close it and reconnect
        var finished = initialClient
        val result = try {
            ResumableSessionDownload(
                connect = { reconnect(keys, deviceId) },
                crcPolicy = CrcPolicy.ReportOnly,
                initialClient = initialClient,
                onSuccess = { finished = it },
            ).download(sessionId, sizeBytes, partial, ::onProgress)
        } catch (e: Throwable) {
            partial.close()
            // The download owned the client; a cancel before its first attempt leaves it open otherwise.
            finished?.close()
            throw e
        }
        // Verified clears an old mark; NotProvided (nothing was transferred this time) keeps it.
        when (result.crc) {
            // The in-memory verdict is set first: a failure to write the mark only costs the retry-after-failed-save case.
            is CrcStatus.Mismatch -> { crcMismatch = true; runCatching { partial.setCrcMismatch(true) } }
            is CrcStatus.Verified -> { crcMismatch = false; runCatching { partial.setCrcMismatch(false) } }
            else -> Unit
        }
        val recordingId = try {
            persistDownloadedSession(persist(partial.length()) { position, length -> partial.read(position, length) })
        } catch (e: Throwable) {
            adoptClient(finished)
            // Only bytes that can't be decrypted are useless. A read error, full disk or database error can be retried,
            // but a read error that repeats would make every Re-download fail the same way until the 7-day sweep.
            when {
                undecryptable -> partial.delete()
                readFailed -> {
                    val failures = runCatching { partial.readFailures() }.getOrDefault(0) + 1
                    if (failures >= MAX_READ_FAILURES) {
                        partial.delete()
                        discardedUnreadablePartial = true
                    } else {
                        runCatching { partial.setReadFailures(failures) }
                        partial.close()
                    }
                }
                else -> {
                    // The partial read back fine this time (the failure was a save, database or cancel), so any
                    // earlier read errors were not permanent.
                    runCatching { partial.setReadFailures(0) }
                    partial.close()
                }
            }
            throw e
        }
        partial.delete()
        return DownloadOutcome(recordingId, finished, crcMismatch, result.crc)
    }

    private class DownloadOutcome(val recordingId: String, val client: PlaudDeviceClient?, val crcMismatch: Boolean, val crc: CrcStatus)

    /** New link and client for a resume attempt; [ResumableSessionDownload] unlocks it. */
    private suspend fun reconnect(keys: DeviceIdentityKeys, deviceId: String?): PlaudDeviceClient {
        checkNotNull(deviceId) { "No device to reconnect to" }
        ble.disconnect()
        ble.connect(deviceId)
        // It outlives the download's withContext block, so it must not collect in that scope.
        return PlaudDeviceClient(ble, createSessionCipher(), keys, notificationScope = viewModelScope)
    }

    private fun showUnlockFailed() {
        _sessionUi.value = DeviceSessionUi(
            recorderDriverId = "plaud",
            statusMessage = DeviceUiCopy.unlockFailed(localeTag()),
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
    }

    /** Takes [next] as the live client, closing the one it replaces so a link is never left open and unreferenced. */
    private fun adoptClient(next: PlaudDeviceClient?) {
        if (client !== next) client?.close()
        client = next
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
            adoptClient(c)
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
            // A hung link's timeout must not leave the screen on "unlocking".
            if (cancelled !is TimeoutCancellationException) throw cancelled
            currentCoroutineContext().ensureActive()
            showUnlockFailed()
        } catch (_: Exception) {
            showUnlockFailed()
        }
    }
}
