package dev.velum.app.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Identity remains distinct even when a later capture reuses a path or recording id. */
data class MicSessionIdentity(val token: Long, val absolutePath: String)
data class OwnedMicRecording(val recordingId: String, val session: MicSessionIdentity)

sealed class MicSessionState {
    open val session: MicSessionIdentity? = null
    data object Idle : MicSessionState()
    data class Starting(override val session: MicSessionIdentity) : MicSessionState()
    data class Recording(val absolutePath: String, val bytesWritten: Long, val token: Long = 0) : MicSessionState() {
        override val session get() = MicSessionIdentity(token, absolutePath)
    }
    data class Paused(val absolutePath: String, val bytesWritten: Long, val token: Long = 0) : MicSessionState() {
        override val session get() = MicSessionIdentity(token, absolutePath)
    }
    data class Stopped(val absolutePath: String, val durationSeconds: Long, val sizeBytes: Long, val token: Long = 0) : MicSessionState() {
        override val session get() = MicSessionIdentity(token, absolutePath)
    }
    data class Failed(val message: String, val permissionRequired: Boolean = false, override val session: MicSessionIdentity? = null) : MicSessionState()
}

fun interface MicPcmSubscription { fun close() }

/** Serializes ownership changes and persistence; the platform also checks every command's identity. */
class MicRecordingController(private val capture: MicCapture, private val canStart: suspend (String) -> Boolean = { true }) {
    private val mutex = Mutex()
    private val draftMutex = Mutex()
    private val pendingDraft = MutableStateFlow<String?>(null)
    private val _owner = MutableStateFlow<OwnedMicRecording?>(null)
    val owner = _owner.asStateFlow()

    suspend fun startOrAttach(recordingId: String, absolutePath: String, inputSource: MicInputSource = MicInputSource.Mic): MicStartResult = mutex.withLock {
        _owner.value?.let { existing ->
            return@withLock if (existing.recordingId == recordingId && existing.session.absolutePath == absolutePath &&
                isCurrent(existing)) MicStartResult.Started(existing.session)
            else MicStartResult.Failed("")
        }
        if ((pendingDraft.value != null && pendingDraft.value != recordingId) || !canStart(recordingId)) return@withLock MicStartResult.Failed("")
        val result = capture.start(absolutePath, inputSource)
        if (result is MicStartResult.Started) {
            if (result.session.token <= 0 || result.session.absolutePath != absolutePath || capture.sessions.value.session != result.session) {
                return@withLock MicStartResult.Failed("")
            }
            _owner.value = OwnedMicRecording(recordingId, result.session)
        }
        result
    }

    fun isCurrent(owned: OwnedMicRecording): Boolean = _owner.value == owned && capture.sessions.value.session == owned.session

    fun attach(recordingId: String, session: MicSessionIdentity, listener: (ByteArray) -> Unit): MicPcmSubscription? {
        val owned = _owner.value ?: return null
        if (owned.recordingId != recordingId || owned.session != session || !isCurrent(owned) || !capture.isCapturing) return null
        val guarded: (ByteArray) -> Unit = { bytes -> if (isCurrent(owned)) listener(bytes) }
        if (!capture.attachPcm(session, guarded)) return null
        return MicPcmSubscription { capture.detachPcm(session, guarded) }
    }

    internal suspend fun withOwner(recordingId: String, path: String, action: suspend (OwnedMicRecording) -> MicStopResult): MicStopResult = mutex.withLock {
        val owned = _owner.value
        if (owned == null || owned.recordingId != recordingId || owned.session.absolutePath != path) MicStopResult.NotCapturing
        else action(owned)
    }

    suspend fun withCurrentOwner(recordingId: String, session: MicSessionIdentity, action: suspend () -> Unit): Boolean = mutex.withLock {
        val owned = _owner.value
        if (owned == null || owned.recordingId != recordingId || owned.session != session || !isCurrent(owned)) false
        else { action(); true }
    }

    fun protects(recordingId: String): Boolean = pendingDraft.value == recordingId || _owner.value?.recordingId == recordingId

    internal suspend fun <T> withPendingDraft(recordingId: String, action: suspend () -> T): T = draftMutex.withLock {
        pendingDraft.value = recordingId
        try { action() } finally { pendingDraft.value = null }
    }

    internal suspend fun <T> inspectInterruptedDrafts(action: suspend () -> T): T = mutex.withLock { action() }

    @kotlin.concurrent.Volatile private var live: Pair<String, String?>? = null

    /** Latest savable live transcript the pane holds, kept after the pane is left so a notification stop can save it. */
    fun publishLiveText(recordingId: String, text: String?) {
        // A late publish after release must not keep plaintext in memory or clobber the next recording's text.
        if (_owner.value?.recordingId == recordingId) live = recordingId to text
    }
    fun liveText(recordingId: String): String? = live?.takeIf { it.first == recordingId }?.second

    /** Runs [action] under the same lock as finalize, so a write cannot interleave with Stop. */
    internal suspend fun <T> underLock(action: suspend () -> T): T = mutex.withLock { action() }

    /** Only admission times out; an admitted permanent delete must finish its file/row writes. */
    internal suspend fun underLock(timeout: kotlin.time.Duration, action: suspend () -> Boolean): Boolean {
        var acquired = false
        try {
            kotlinx.coroutines.withTimeoutOrNull(timeout) { mutex.lock(); acquired = true }
            return if (acquired) action() else false
        } finally {
            if (acquired) mutex.unlock()
        }
    }

    @kotlin.concurrent.Volatile private var uiStopId: String? = null

    /** The in-app Stop is about to finalize this recording itself (with the live text). */
    fun beginUiStop(recordingId: String) { uiStopId = recordingId }
    fun isUiStopping(recordingId: String): Boolean = uiStopId == recordingId

    internal fun release(owned: OwnedMicRecording) {
        if (_owner.compareAndSet(owned, null)) {
            if (uiStopId == owned.recordingId) uiStopId = null
            if (live?.first == owned.recordingId) live = null
        }
    }
}
