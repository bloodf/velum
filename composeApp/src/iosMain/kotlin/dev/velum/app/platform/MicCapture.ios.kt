@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.velum.app.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.AVFAudio.*
import platform.AudioToolbox.kAudioFormatLinearPCM
import platform.Foundation.*
import platform.darwin.NSObject

actual fun createMicCapture(): MicCapture = IosMicCapture()

/** AVAudioRecorder owns the WAV header. Live PCM requires a separate capture backend. */
private class IosMicCapture : NSObject(), MicCapture, AVAudioRecorderDelegateProtocol {
    private val state = MutableStateFlow<MicSessionState>(MicSessionState.Idle)
    private val failure = MutableStateFlow<String?>(null)
    override val sessions = state.asStateFlow()
    override val failures = failure.asStateFlow()
    override val isSupported = true
    override val isCapturing get() = recorder != null
    override val isPaused get() = state.value is MicSessionState.Paused
    override var onPcmChunk: ((ByteArray) -> Unit)? = null
    override fun attachPcm(session: MicSessionIdentity, listener: (ByteArray) -> Unit) = false
    private var recorder: AVAudioRecorder? = null
    private var observer: NSObjectProtocol? = null
    private var nextToken = 0L
    private var bookmarks = emptyList<MicMarker>()
    override val markers get() = bookmarks

    // No suspension inside a native transition: cancellation cannot strand an admitted recorder.
    private suspend inline fun <T> transition(crossinline action: suspend () -> T): T =
        withContext(Dispatchers.Main.immediate + NonCancellable) { action() }

    override suspend fun start(absolutePath: String, inputSource: MicInputSource): MicStartResult = transition {
        if (isCapturing) return@transition MicStartResult.Failed("Microphone is already recording")
        val audio = AVAudioSession.sharedInstance()
        if (audio.recordPermission != AVAudioSessionRecordPermissionGranted) return@transition MicStartResult.PermissionRequired
        if (!absolutePath.startsWith("/") || !absolutePath.endsWith(".wav", ignoreCase = true)) {
            return@transition MicStartResult.Failed("Recording requires an absolute WAV path")
        }
        if (NSFileManager.defaultManager.fileExistsAtPath(absolutePath)) {
            return@transition MicStartResult.Failed("Recording file already exists")
        }
        val identity = MicSessionIdentity(++nextToken, absolutePath)
        state.value = MicSessionState.Starting(identity)
        failure.value = null
        bookmarks = emptyList()
        onPcmChunk = null
        if (!audio.setCategory(AVAudioSessionCategoryPlayAndRecord,
                withOptions = AVAudioSessionCategoryOptionDefaultToSpeaker or AVAudioSessionCategoryOptionAllowBluetooth,
                error = null) || !audio.setActive(true, error = null)) {
            fail("Unable to activate microphone session", identity)
            return@transition MicStartResult.Failed(failure.value!!)
        }
        val settings: Map<Any?, Any> = mapOf(
            AVFormatIDKey to kAudioFormatLinearPCM,
            AVSampleRateKey to 16000.0,
            AVNumberOfChannelsKey to 1,
            AVLinearPCMBitDepthKey to 16,
            AVLinearPCMIsFloatKey to false,
            AVLinearPCMIsBigEndianKey to false,
        )
        val native = AVAudioRecorder(NSURL.fileURLWithPath(absolutePath), settings, null)
        recorder = native
        native.delegate = this
        if (!native.prepareToRecord() || !native.record()) {
            fail("Unable to start microphone recording", identity)
            return@transition MicStartResult.Failed(failure.value!!)
        }
        observer = NSNotificationCenter.defaultCenter.addObserverForName(
            AVAudioSessionInterruptionNotification, audio, NSOperationQueue.mainQueue,
        ) { notification ->
            val kind = notification?.userInfo?.get(AVAudioSessionInterruptionTypeKey) as? NSNumber
            if (kind?.unsignedIntegerValue == AVAudioSessionInterruptionTypeBegan && recorder === native) {
                native.pause()
                state.value = MicSessionState.Paused(absolutePath, bytesWritten(), identity.token)
            }
        }
        state.value = MicSessionState.Recording(absolutePath, 0, identity.token)
        MicStartResult.Started(identity)
    }

    override suspend fun pause(): MicPauseResult = transition {
        val native = recorder ?: return@transition MicPauseResult.NotCapturing
        native.pause()
        val identity = state.value.session!!
        state.value = MicSessionState.Paused(identity.absolutePath, bytesWritten(), identity.token)
        MicPauseResult.Ok
    }

    override suspend fun resume(): MicPauseResult = transition {
        val native = recorder ?: return@transition MicPauseResult.NotCapturing
        val identity = state.value.session!!
        if (!AVAudioSession.sharedInstance().setActive(true, error = null) || !native.record()) {
            fail("Unable to resume microphone recording", identity)
            return@transition MicPauseResult.Failed(failure.value!!)
        }
        state.value = MicSessionState.Recording(identity.absolutePath, bytesWritten(), identity.token)
        MicPauseResult.Ok
    }

    override suspend fun addMarker(label: String): MicMarkerResult = transition {
        val native = recorder ?: return@transition MicMarkerResult.NotCapturing
        val marker = MicMarker((native.currentTime * 1000).toLong(), label)
        bookmarks = bookmarks + marker
        MicMarkerResult.Ok(marker)
    }

    override suspend fun stop(): MicStopResult = transition {
        val previous = state.value
        if (previous is MicSessionState.Stopped) return@transition previous.result()
        if (previous is MicSessionState.Failed) return@transition MicStopResult.Failed(previous.message)
        val native = recorder ?: return@transition MicStopResult.NotCapturing
        val identity = previous.session!!
        val duration = native.currentTime.toLong()
        cleanup()
        // A delegate error during stop must remain terminal.
        if (state.value is MicSessionState.Failed) return@transition MicStopResult.Failed(failure.value!!)
        val size = fileSize(identity.absolutePath)
        if (size <= 44) {
            fail("Microphone recording contains no audio", identity)
            return@transition MicStopResult.Failed(failure.value!!)
        }
        val stopped = MicSessionState.Stopped(identity.absolutePath, duration, size, identity.token)
        state.value = stopped
        onPcmChunk = null
        stopped.result()
    }

    // Check identity on the same main-thread transition as the native operation.
    override suspend fun stop(session: MicSessionIdentity): MicStopResult = transition {
        if (state.value.session == session) stop() else MicStopResult.NotCapturing
    }

    override suspend fun pause(session: MicSessionIdentity): MicPauseResult = transition {
        if (state.value.session == session) pause() else MicPauseResult.NotCapturing
    }

    override suspend fun resume(session: MicSessionIdentity): MicPauseResult = transition {
        if (state.value.session == session) resume() else MicPauseResult.NotCapturing
    }

    override suspend fun addMarker(session: MicSessionIdentity, label: String): MicMarkerResult = transition {
        if (state.value.session == session) addMarker(label) else MicMarkerResult.NotCapturing
    }

    private fun MicSessionState.Stopped.result() = MicStopResult.Stopped(
        absolutePath, durationSeconds, sizeBytes, session,
    )
    private fun fileSize(path: String): Long =
        (NSFileManager.defaultManager.attributesOfItemAtPath(path, null)?.get(NSFileSize) as? NSNumber)?.longLongValue ?: 0L
    private fun bytesWritten() = state.value.session?.let { (fileSize(it.absolutePath) - 44).coerceAtLeast(0) } ?: 0L

    private fun cleanup() {
        observer?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        observer = null
        val native = recorder
        recorder = null
        native?.delegate = null
        native?.stop()
        AVAudioSession.sharedInstance().setActive(false,
            withOptions = AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation, error = null)
    }
    private fun fail(message: String, identity: MicSessionIdentity) {
        cleanup()
        onPcmChunk = null
        failure.value = message
        state.value = MicSessionState.Failed(message, session = identity)
    }
    override fun audioRecorderEncodeErrorDidOccur(recorder: AVAudioRecorder, error: NSError?) {
        if (this.recorder === recorder) state.value.session?.let { fail("Microphone encoding failed", it) }
    }
    override fun audioRecorderDidFinishRecording(recorder: AVAudioRecorder, successfully: Boolean) {
        if (this.recorder === recorder) state.value.session?.let { fail("Microphone recording ended unexpectedly", it) }
    }
}
