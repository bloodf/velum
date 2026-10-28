package dev.velum.app.download

import dev.velum.app.i18n.DeviceUiCopy
import dev.velum.app.platform.DownloadNotifyKind
import dev.velum.app.platform.DownloadProgressNotifier
import dev.velum.app.platform.SessionDownloadKeepAliveGate
import dev.velum.app.ui.DownloadedSessionAudio
import dev.velum.ble.client.PlaudDeviceClient
import dev.velum.ble.client.createTempSessionFile
import dev.velum.ble.crypto.createFileDecryptor
import dev.velum.protocol.PlaudFileCrypto
import dev.velum.protocol.SessionEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * App-scoped owner for Plaud session downloads (BLE / SoftAP).
 *
 * Lives on [AppContainer]'s [SupervisorJob] scope so a ViewModel clear / task
 * swipe cannot cancel an in-flight transfer. [SessionDownloadService] keeps the
 * process alive while [SessionDownloadKeepAliveGate] is active.
 */
data class SessionDownloadUiState(
    val downloadingSessionId: Long? = null,
    val downloadProgress: Float? = null,
    val statusMessage: String? = null,
    val lastDownloadedRecordingId: String? = null,
    val sessionsUpdate: List<SessionEntry>? = null,
)

data class SessionDownloadRequest(
    val sessionId: Long,
    val sizeBytes: Long,
    val privateKeyPem: String,
    val deviceSerial: String?,
    val startedAtEpochSeconds: Long,
    /**
     * Bound BLE/Wi-Fi client for the live transfer. Tests that inject a fake
     * [SessionDownloadTransfer] may leave this null.
     */
    val client: PlaudDeviceClient? = null,
)

fun interface SessionDownloadTransfer {
    suspend fun run(
        request: SessionDownloadRequest,
        onProgress: (receivedBytes: Long) -> Unit,
    ): SessionDownloadResult
}

data class SessionDownloadResult(
    val recordingId: String,
    val sessions: List<SessionEntry>,
)

fun liveSessionDownloadTransfer(
    persistDownloadedSession: suspend (DownloadedSessionAudio) -> String,
): SessionDownloadTransfer = SessionDownloadTransfer { request, onProgress ->
    val client = requireNotNull(request.client) { "PlaudDeviceClient required for live download" }
    val temp = createTempSessionFile()
    val encrypted = if (temp != null) {
        try {
            client.downloadSessionTo(request.sessionId, request.sizeBytes, temp, onProgress)
            temp.readAll()
        } finally {
            temp.delete()
        }
    } else {
        client.downloadSession(request.sessionId, request.sizeBytes, onProgress)
    }
    val decrypted = PlaudFileCrypto.decrypt(encrypted, request.privateKeyPem, createFileDecryptor())
    val recordingId = persistDownloadedSession(
        DownloadedSessionAudio(
            sessionId = request.sessionId,
            deviceSerial = request.deviceSerial,
            startedAtEpochSeconds = request.startedAtEpochSeconds,
            sizeBytes = decrypted.size.toLong(),
            decryptedBytes = decrypted,
        ),
    )
    val sessions = client.listSessions().entries
    SessionDownloadResult(recordingId = recordingId, sessions = sessions)
}

class SessionDownloadManager(
    private val scope: CoroutineScope,
    private val localeTag: () -> String,
    private val transfer: SessionDownloadTransfer,
    private val keepAlive: SessionDownloadKeepAliveGate = SessionDownloadKeepAliveGate(),
) {
    private val _state = MutableStateFlow(SessionDownloadUiState())
    val state: StateFlow<SessionDownloadUiState> = _state.asStateFlow()

    private val mutex = Mutex()
    private var downloadJob: Job? = null

    val isDownloadActive: Boolean
        get() = downloadJob?.isActive == true

    val keepAliveActive: Boolean
        get() = keepAlive.isActive

    fun start(request: SessionDownloadRequest) {
        if (downloadJob?.isActive == true && _state.value.downloadingSessionId != null) return
        val previous = downloadJob
        val started = scope.launch {
            previous?.join()
            try {
                mutex.withLock {
                    runDownload(request)
                }
            } finally {
                // Single release point for success / failure / cancel.
                keepAlive.onDownloadEnd()
                DownloadProgressNotifier.dismiss(DownloadNotifyKind.DeviceSession)
                DownloadProgressNotifier.setCancelHandler(DownloadNotifyKind.DeviceSession, null)
            }
        }
        downloadJob = started
        started.invokeOnCompletion {
            if (downloadJob === started) downloadJob = null
        }
    }

    fun cancel() {
        downloadJob?.cancel()
    }

    suspend fun join() {
        downloadJob?.join()
    }

    private suspend fun runDownload(request: SessionDownloadRequest) {
        val locale = localeTag()
        val totalBytes = request.sizeBytes.coerceAtLeast(1L)
        _state.value = SessionDownloadUiState(
            downloadingSessionId = request.sessionId,
            downloadProgress = 0f,
            statusMessage = DeviceUiCopy.downloadingSession(locale, request.sessionId),
            lastDownloadedRecordingId = null,
            sessionsUpdate = null,
        )
        keepAlive.onDownloadBegin()
        DownloadProgressNotifier.ensureChannel()
        DownloadProgressNotifier.setCancelHandler(DownloadNotifyKind.DeviceSession) { cancel() }
        DownloadProgressNotifier.show(
            kind = DownloadNotifyKind.DeviceSession,
            title = DeviceUiCopy.downloadNotificationTitle(locale),
            body = DeviceUiCopy.downloadingSession(locale, request.sessionId),
            progress = 0f,
            cancelLabel = DeviceUiCopy.cancel(locale),
        )
        try {
            val result = withContext(Dispatchers.IO) {
                transfer.run(request) { received ->
                    val progress = (received.toDouble() / totalBytes.toDouble())
                        .toFloat()
                        .coerceIn(0f, 0.99f)
                    _state.value = _state.value.copy(downloadProgress = progress)
                    DownloadProgressNotifier.update(DownloadNotifyKind.DeviceSession, progress)
                }
            }
            _state.value = SessionDownloadUiState(
                downloadingSessionId = null,
                downloadProgress = null,
                lastDownloadedRecordingId = result.recordingId,
                sessionsUpdate = result.sessions,
                statusMessage = DeviceUiCopy.downloadedSession(localeTag(), request.sessionId),
            )
        } catch (e: CancellationException) {
            _state.value = SessionDownloadUiState(
                downloadingSessionId = null,
                downloadProgress = null,
                statusMessage = DeviceUiCopy.downloadCancelled(localeTag()),
            )
            throw e
        } catch (_: Exception) {
            _state.value = SessionDownloadUiState(
                downloadingSessionId = null,
                downloadProgress = null,
                statusMessage = DeviceUiCopy.downloadFailed(localeTag()),
            )
        }
    }
}
