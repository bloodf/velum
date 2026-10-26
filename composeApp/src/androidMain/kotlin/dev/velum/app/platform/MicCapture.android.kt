package dev.velum.app.platform

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

private var micAppContext: android.content.Context? = null

fun installMicCaptureContext(context: android.content.Context) {
    micAppContext = context.applicationContext
}

actual fun createMicCapture(): MicCapture = AndroidMicCapture { micAppContext }

private class AndroidMicCapture(
    private val contextProvider: () -> android.content.Context?,
) : MicCapture {
    override val isSupported: Boolean = true

    override val isCapturing: Boolean
        get() = when (MicRecordingService.state.value) {
            is MicSessionState.Recording,
            is MicSessionState.Paused,
            -> true
            else -> false
        }

    override val isPaused: Boolean
        get() = MicRecordingService.state.value is MicSessionState.Paused

    override var onPcmChunk: ((ByteArray) -> Unit)?
        get() = MicRecordingService.pcmListener
        set(value) {
            MicRecordingService.pcmListener = value
        }

    override fun start(absolutePath: String): MicStartResult {
        val context = contextProvider()
            ?: return MicStartResult.Failed("No Android context")
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return MicStartResult.PermissionRequired
        if (isCapturing) return MicStartResult.Failed("Already capturing")

        MicRecordingService.resetIdle()
        MicRecordingService.start(context, absolutePath)

        val outcome = awaitState(timeoutMs = 8_000) { state ->
            when (state) {
                is MicSessionState.Recording -> MicStartResult.Started
                is MicSessionState.Failed -> MicStartResult.Failed(state.message)
                else -> null
            }
        }
        return outcome ?: MicStartResult.Failed("Timed out starting microphone service")
    }

    override fun pause(): MicPauseResult {
        val context = contextProvider()
            ?: return MicPauseResult.Failed("No Android context")
        if (!isCapturing) return MicPauseResult.NotCapturing
        if (isPaused) return MicPauseResult.Ok
        MicRecordingService.pause(context)
        val outcome = awaitState(timeoutMs = 3_000) { state ->
            when (state) {
                is MicSessionState.Paused -> MicPauseResult.Ok
                is MicSessionState.Failed -> MicPauseResult.Failed(state.message)
                else -> null
            }
        }
        return outcome ?: MicPauseResult.Failed("Timed out pausing")
    }

    override fun resume(): MicPauseResult {
        val context = contextProvider()
            ?: return MicPauseResult.Failed("No Android context")
        if (!isCapturing) return MicPauseResult.NotCapturing
        if (!isPaused) return MicPauseResult.Ok
        MicRecordingService.resume(context)
        val outcome = awaitState(timeoutMs = 3_000) { state ->
            when (state) {
                is MicSessionState.Recording -> MicPauseResult.Ok
                is MicSessionState.Failed -> MicPauseResult.Failed(state.message)
                else -> null
            }
        }
        return outcome ?: MicPauseResult.Failed("Timed out resuming")
    }

    override fun stop(): MicStopResult {
        val context = contextProvider()
            ?: return MicStopResult.Failed("No Android context")
        if (!isCapturing && MicRecordingService.state.value !is MicSessionState.Stopped) {
            return MicStopResult.NotCapturing
        }
        val already = MicRecordingService.state.value
        if (already is MicSessionState.Stopped) {
            MicRecordingService.resetIdle()
            return MicStopResult.Stopped(
                absolutePath = already.absolutePath,
                durationSeconds = already.durationSeconds,
                sizeBytes = already.sizeBytes,
            )
        }
        MicRecordingService.stop(context)
        val outcome = awaitState(timeoutMs = 8_000) { state ->
            when (state) {
                is MicSessionState.Stopped -> MicStopResult.Stopped(
                    absolutePath = state.absolutePath,
                    durationSeconds = state.durationSeconds,
                    sizeBytes = state.sizeBytes,
                )
                is MicSessionState.Failed -> MicStopResult.Failed(state.message)
                is MicSessionState.Idle -> MicStopResult.NotCapturing
                else -> null
            }
        }
        MicRecordingService.resetIdle()
        return outcome ?: MicStopResult.Failed("Timed out stopping microphone service")
    }

    private fun <T> awaitState(timeoutMs: Long, map: (MicSessionState) -> T?): T? {
        map(MicRecordingService.state.value)?.let { return it }
        return runBlocking {
            withTimeoutOrNull(timeoutMs.coerceAtLeast(1)) {
                val state = MicRecordingService.state.first { map(it) != null }
                map(state)
            }
        }
    }
}
