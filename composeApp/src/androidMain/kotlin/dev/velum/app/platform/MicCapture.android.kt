package dev.velum.app.platform

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dev.velum.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

private var micAppContext: android.content.Context? = null
fun installMicCaptureContext(context: android.content.Context) { micAppContext = context.applicationContext }
actual fun createMicCapture(): MicCapture = AndroidMicCapture(contextProvider = { micAppContext })

internal class AndroidMicCapture(
    private val contextProvider: () -> android.content.Context?,
    override val sessions: StateFlow<MicSessionState> = MicRecordingService.state,
    private val launchService: (android.content.Context, MicSessionIdentity, MicInputSource) -> Unit = MicRecordingService::start,
    private val requestStop: (android.content.Context, MicSessionIdentity) -> Unit = MicRecordingService::cancelPendingStart,
    private val stopService: (android.content.Context, MicSessionIdentity) -> Unit = MicRecordingService::stop,
) : MicCapture {
    private val startMutex = Mutex()
    private var startedSession: MicSessionIdentity? = null
    private var legacyListener: ((ByteArray) -> Unit)? = null
    override val failures: StateFlow<String?> = MicRecordingService.failures
    override val isSupported = true
    override val isCapturing get() = sessions.value is MicSessionState.Starting || sessions.value is MicSessionState.Recording || sessions.value is MicSessionState.Paused
    override val isPaused get() = sessions.value is MicSessionState.Paused
    override val markers get() = MicRecordingService.snapshotMarkers()
    override var onPcmChunk: ((ByteArray) -> Unit)?
        get() = legacyListener
        set(value) {
            val session = startedSession ?: return
            legacyListener?.let { detachPcm(session, it) }
            legacyListener = value?.takeIf { attachPcm(session, it) }
        }
    override fun attachPcm(session: MicSessionIdentity, listener: (ByteArray) -> Unit) = MicRecordingService.attachPcm(session, listener)
    override fun detachPcm(session: MicSessionIdentity, listener: (ByteArray) -> Unit) = MicRecordingService.detachPcm(session, listener)

    override suspend fun start(absolutePath: String, inputSource: MicInputSource): MicStartResult {
        val context = contextProvider() ?: return MicStartResult.Failed("No Android context")
        currentCoroutineContext().ensureActive()
        if (!startMutex.tryLock()) return MicStartResult.Failed(context.getString(R.string.mic_recording_start_failed))
        var requested: MicSessionIdentity? = null
        var started = false
        try {
            currentCoroutineContext().ensureActive()
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return MicStartResult.PermissionRequired
            if (isCapturing) return MicStartResult.Failed(context.getString(R.string.mic_recording_start_failed))
            val session = MicRecordingService.newSession(absolutePath).also { requested = it; startedSession = it }
            try { launchService(context, session, inputSource) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: SecurityException) {
                return if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) MicStartResult.PermissionRequired
                else MicStartResult.Failed(context.getString(R.string.mic_recording_start_failed))
            } catch (_: Exception) { return MicStartResult.Failed(context.getString(R.string.mic_recording_start_failed)) }
            val outcome = awaitState(8_000) { state ->
                when {
                    state.session == session && state is MicSessionState.Recording -> MicStartResult.Started(session)
                    state.session == session && state is MicSessionState.Failed -> if (state.permissionRequired) MicStartResult.PermissionRequired else MicStartResult.Failed(state.message)
                    state.session != null && state.session!!.token >= session.token && state.session != session -> MicStartResult.Failed(context.getString(R.string.mic_recording_start_failed))
                    else -> null
                }
            }
            started = outcome is MicStartResult.Started
            return outcome ?: MicStartResult.Failed(context.getString(R.string.mic_recording_start_failed))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            if (!started) requested?.let { try { requestStop(context, it) } catch (_: Exception) { } }
            startMutex.unlock()
        }
    }

    override suspend fun pause(): MicPauseResult = sessions.value.session?.let { pause(it) } ?: MicPauseResult.NotCapturing
    override suspend fun pause(session: MicSessionIdentity): MicPauseResult = changePaused(session, paused = true)
    override suspend fun resume(): MicPauseResult = sessions.value.session?.let { resume(it) } ?: MicPauseResult.NotCapturing
    override suspend fun resume(session: MicSessionIdentity): MicPauseResult = changePaused(session, paused = false)
    private suspend fun changePaused(session: MicSessionIdentity, paused: Boolean): MicPauseResult {
        val context = contextProvider() ?: return MicPauseResult.Failed("No Android context")
        if (sessions.value.session != session || !isCapturing) return MicPauseResult.NotCapturing
        if (isPaused == paused) return MicPauseResult.Ok
        if (paused) MicRecordingService.pause(context, session) else MicRecordingService.resume(context, session)
        return awaitState(3_000) { state ->
            when {
                state.session != session -> MicPauseResult.NotCapturing
                state is MicSessionState.Failed -> MicPauseResult.Failed(state.message)
                paused && state is MicSessionState.Paused || !paused && state is MicSessionState.Recording -> MicPauseResult.Ok
                else -> null
            }
        } ?: MicPauseResult.Failed(context.getString(R.string.mic_recording_capture_failed))
    }
    override suspend fun addMarker(label: String): MicMarkerResult = sessions.value.session?.let { addMarker(it, label) } ?: MicMarkerResult.NotCapturing
    override suspend fun addMarker(session: MicSessionIdentity, label: String): MicMarkerResult {
        val context = contextProvider() ?: return MicMarkerResult.Failed("No Android context")
        if (sessions.value.session != session || !isCapturing) return MicMarkerResult.NotCapturing
        val before = markers.size
        MicRecordingService.mark(context, session, label)
        repeat(40) {
            if (sessions.value.session != session) return MicMarkerResult.NotCapturing
            val list = markers
            if (list.size > before) return MicMarkerResult.Ok(list.last())
            kotlinx.coroutines.delay(25)
        }
        return MicMarkerResult.Failed(context.getString(R.string.mic_recording_capture_failed))
    }
    override suspend fun stop(): MicStopResult = sessions.value.session?.let { stop(it) } ?: MicStopResult.NotCapturing
    override suspend fun stop(session: MicSessionIdentity): MicStopResult {
        val context = contextProvider() ?: return MicStopResult.Failed("No Android context")
        fun result(state: MicSessionState): MicStopResult? = when {
            state.session != session -> MicStopResult.NotCapturing
            state is MicSessionState.Stopped -> MicStopResult.Stopped(state.absolutePath, state.durationSeconds, state.sizeBytes, session)
            state is MicSessionState.Failed -> MicStopResult.Failed(state.message)
            else -> null
        }
        result(sessions.value)?.let { return it }
        stopService(context, session)
        // Retain the identified terminal state: cancellation and concurrent callers may retry.
        return awaitState(8_000, ::result) ?: MicStopResult.Failed(context.getString(R.string.mic_recording_capture_failed))
    }
    private suspend fun <T> awaitState(timeoutMs: Long, map: (MicSessionState) -> T?): T? {
        map(sessions.value)?.let { return it }
        return withTimeoutOrNull(timeoutMs) { map(sessions.first { map(it) != null }) }
    }
}
