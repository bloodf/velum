package dev.velum.app.platform

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.velum.ai.pipeline.inAppLanguage
import dev.velum.app.R
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * True microphone foreground service: AudioRecord + periodic WAV header rewrite/fsync,
 * persistent notification with Pause/Stop. Survives backgrounding and screen-off.
 *
 * Invariants:
 * - [startForeground] runs on the main thread **before** disk / AudioRecord init.
 * - AudioRecord I/O and [Thread.join] never run on the main thread (ANR).
 * - Pause/resume are flag-only; the read loop keeps AudioRecord started.
 */
class MicRecordingService : Service() {

    private val capturing = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    @Volatile private var audioRecord: ManagedMicInput? = null
    internal var runtime: MicServiceRuntime = AndroidMicServiceRuntime
    private val sessionLock = Any()
    private var generation = 0L
    private var currentSession: MicSessionIdentity? = null
    private var destroyed = false
    private var latestStartId = 0
    @Volatile private var recordThread: Thread? = null
    @Volatile private var outputFile: File? = null
    @Volatile private var bytesWritten: Long = 0
    @Volatile private var startedAtElapsedMs: Long = 0
    @Volatile private var pausedAccumulatedMs: Long = 0
    @Volatile private var pauseStartedElapsedMs: Long = 0

    override fun onCreate() { super.onCreate(); currentService = this }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = synchronized(sessionLock) {
        if (destroyed) return@synchronized START_NOT_STICKY
        latestStartId = startId
        when (intent?.action) {
            ACTION_START -> {
                val path = intent.getStringExtra(EXTRA_PATH)
                if (path.isNullOrBlank()) {
                    if (!capturing.get()) {
                        publish(MicSessionState.Failed(inAppLanguage().getString(R.string.mic_recording_start_failed)))
                        stopSelfSafely()
                    }
                    return@synchronized START_NOT_STICKY
                }
                val sourceName = intent.getStringExtra(EXTRA_INPUT_SOURCE) ?: MicInputSource.Mic.name
                val input = runCatching { MicInputSource.valueOf(sourceName) }.getOrDefault(MicInputSource.Mic)
                // Permission is checked on main, then FGS promotion precedes disk/audio work.
                val requested = MicSessionIdentity(intent.getLongExtra(EXTRA_SESSION_TOKEN, 0).takeIf { it > 0 } ?: nextSessionToken.incrementAndGet(), path)
                if (requested.token <= cancelledThrough.get() || requested.token <= sessionSequence.get()) {
                    if (!capturing.get()) stopSelfSafely()
                    return@synchronized START_NOT_STICKY
                }
                val token = promoteToForeground(requested) ?: return@synchronized START_NOT_STICKY
                try {
                    runtime.launch("velum-mic-init") { beginRecordingAfterForeground(token, path, input) }
                } catch (_: Exception) {
                    failAndStop(token, R.string.mic_recording_start_failed)
                }
            }
            ACTION_PAUSE -> if (matches(intent)) pauseRecording()
            ACTION_RESUME -> if (matches(intent)) resumeRecording()
            ACTION_MARK -> if (matches(intent)) dropMarker(intent.getStringExtra(EXTRA_MARK_LABEL).orEmpty())
            ACTION_STOP -> if (matches(intent)) {
                // Join + finalize off main — onStartCommand must not block.
                val token = generation
                runtime.launch("velum-mic-stop") { stopRecording(fromUser = true, token = token) }
            }
            else -> Unit
        }
        // NOT_STICKY: process death is recovered via WavWriter.recoverOrFinalize, not sticky restart.
        START_NOT_STICKY
    }

    override fun onDestroy() {
        val token = synchronized(sessionLock) {
            destroyed = true
            try { if (sessionSequence.get() == generation) runtime.removeForeground(this) } catch (_: Exception) { /* Destruction still closes audio below. */ }
            generation
        }
        if (capturing.get()) {
            runtime.launch("velum-mic-destroy-stop") { stopRecording(fromUser = false, token = token) }
        }
        if (currentService === this) currentService = null
        super.onDestroy()
    }

    private fun matches(intent: Intent): Boolean = currentSession?.let {
        it.token == intent.getLongExtra(EXTRA_SESSION_TOKEN, -1) && it.absolutePath == intent.getStringExtra(EXTRA_PATH)
    } == true

    private fun hasMicrophonePermission(): Boolean = ContextCompat.checkSelfPermission(
        this, Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    /** Promote immediately when permitted; otherwise stop the pending FGS request immediately. */
    private fun promoteToForeground(requested: MicSessionIdentity): Long? = synchronized(sessionLock) {
        if (destroyed) return@synchronized null
        if (!capturing.compareAndSet(false, true)) return@synchronized null
        val token = requested.token
        sessionSequence.set(token)
        generation = token
        currentSession = requested
        publish(MicSessionState.Starting(requested))
        if (!hasMicrophonePermission()) {
            failAndStop(token, R.string.mic_recording_permission_required, permissionRequired = true)
            return@synchronized null
        }
        try {
            ensureNotificationChannel(this)
            runtime.promote(this, buildNotification(paused = false),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        } catch (_: SecurityException) {
            val denied = !hasMicrophonePermission()
            failAndStop(token, if (denied) R.string.mic_recording_permission_required else R.string.mic_recording_start_failed, denied)
            return@synchronized null
        } catch (_: Exception) {
            failAndStop(token, R.string.mic_recording_start_failed)
            return@synchronized null
        }
        token
    }

    private fun active(token: Long): Boolean = synchronized(sessionLock) {
        generation == token && sessionSequence.get() == token && !destroyed && capturing.get()
    }

    private fun beginRecordingAfterForeground(token: Long, absolutePath: String, inputSource: MicInputSource) {
        var recorder: ManagedMicInput? = null
        var handedToReader = false
        try {
            if (!active(token)) return
            // Recheck after promotion: revocation can race both FGS and AudioRecord startup.
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                failAndStop(token, R.string.mic_recording_permission_required, permissionRequired = true)
                return
            }
            synchronized(markersLock) { sessionMarkers.clear() }
            val file = File(absolutePath)
            file.parentFile?.mkdirs()
            check(!file.exists()) { "Recording output already exists." }
            val input = runtime.createInput(resolveAudioSource(inputSource))
            recorder = ManagedMicInput(input)
            check(input.initialized) { "AudioRecord failed to initialize." }
            if (!active(token)) return
            FileChannel.open(file.toPath(), CREATE_NEW, WRITE).use { channel ->
                val header = ByteBuffer.wrap(pcm16MonoWavHeader(0, SAMPLE_RATE_HZ))
                while (header.hasRemaining()) check(channel.write(header) > 0) { "Unable to initialize recording header." }
                channel.force(true)
            }
            if (!active(token)) return
            recorder.start()
            synchronized(sessionLock) {
                if (!active(token)) return
                outputFile = file
                bytesWritten = 0
                startedAtElapsedMs = SystemClock.elapsedRealtime()
                pausedAccumulatedMs = 0
                pauseStartedElapsedMs = 0
                paused.set(false)
                audioRecord = recorder
                publish(MicSessionState.Recording(file.absolutePath, 0, token))
                val owned = checkNotNull(recorder)
                recordThread = runtime.launch("velum-mic-fgs") { readAudio(token, owned, file) }
                handedToReader = true
            }
        } catch (_: SecurityException) {
            failAndStop(token, R.string.mic_recording_permission_required, permissionRequired = true)
        } catch (_: CancellationException) {
            failAndStop(token, R.string.mic_recording_cancelled)
        } catch (_: Exception) {
            failAndStop(token, R.string.mic_recording_start_failed)
        } finally {
            if (!handedToReader) recorder?.release()
        }
    }

    private fun readAudio(token: Long, recorder: ManagedMicInput, file: File) {
        val buffer = ByteArray(recorder.input.bufferSize)
        var sinceSync = 0L
        var written = 0L
        try {
            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(44)
                try {
                    while (active(token)) {
                        if (paused.get()) { SystemClock.sleep(50); continue }
                        val n = recorder.input.read(buffer)
                        if (!active(token)) break
                        check(n >= 0) { "AudioRecord read failed." }
                        if (n > 0) {
                            raf.write(buffer, 0, n)
                            written += n
                            sinceSync += n
                            val listener = synchronized(sessionLock) {
                                if (active(token)) { bytesWritten = written; pcmBinding?.takeIf { it.session == currentSession }?.listener } else null
                            }
                            listener?.invoke(buffer.copyOf(n))
                            if (sinceSync >= SYNC_EVERY_BYTES) {
                                raf.seek(0)
                                writeWavHeaderTo(raf, written, SAMPLE_RATE_HZ)
                                raf.fd.sync()
                                raf.seek(44 + written)
                                sinceSync = 0
                                synchronized(sessionLock) {
                                    if (active(token)) {
                                        val state = state.value
                                        if (state is MicSessionState.Recording) publish(state.copy(bytesWritten = written))
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    raf.seek(0)
                    writeWavHeaderTo(raf, written, SAMPLE_RATE_HZ)
                    raf.fd.sync()
                }
            }
        } catch (_: SecurityException) {
            failAndStop(token, R.string.mic_recording_permission_required, permissionRequired = true)
        } catch (_: Exception) {
            if (active(token)) failAndStop(token, R.string.mic_recording_capture_failed)
        } finally {
            recorder.release()
            synchronized(sessionLock) { if (audioRecord === recorder) audioRecord = null }
        }
    }

    private fun failAndStop(token: Long, message: Int, permissionRequired: Boolean = false) = synchronized(sessionLock) {
        if (generation != token || sessionSequence.get() != token || !capturing.get()) return@synchronized
        // An old initializer/reader cannot overwrite a later stop or newer session.
        capturing.set(false)
        paused.set(false)
        pcmListener = null
        publish(MicSessionState.Failed(inAppLanguage().getString(message), permissionRequired, currentSession))
        outputFile = null
        stopSelfSafely()
    }

    private fun recordedOffsetMs(): Long {
        if (startedAtElapsedMs <= 0L) return 0L
        val now = SystemClock.elapsedRealtime()
        val pausedExtra = if (paused.get() && pauseStartedElapsedMs > 0) {
            now - pauseStartedElapsedMs
        } else {
            0L
        }
        return (now - startedAtElapsedMs - pausedAccumulatedMs - pausedExtra).coerceAtLeast(0L)
    }

    private fun dropMarker(label: String) {
        if (!capturing.get()) return
        val marker = MicMarker(offsetMs = recordedOffsetMs(), label = label)
        synchronized(markersLock) {
            sessionMarkers += marker
        }
    }

    /** Pause is flag-only — never AudioRecord.stop() while the read loop may be in read(). */
    private fun pauseRecording() {
        if (!capturing.get() || !paused.compareAndSet(false, true)) return
        pauseStartedElapsedMs = SystemClock.elapsedRealtime()
        val path = outputFile?.absolutePath ?: return
        publish(MicSessionState.Paused(absolutePath = path, bytesWritten = bytesWritten, token = generation))
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, buildNotification(paused = true))
    }

    /** Resume clears the pause flag; AudioRecord stays started for the whole session. */
    private fun resumeRecording() {
        if (!capturing.get() || !paused.compareAndSet(true, false)) return
        if (pauseStartedElapsedMs > 0) {
            pausedAccumulatedMs += SystemClock.elapsedRealtime() - pauseStartedElapsedMs
            pauseStartedElapsedMs = 0
        }
        val path = outputFile?.absolutePath ?: return
        publish(MicSessionState.Recording(absolutePath = path, bytesWritten = bytesWritten, token = generation))
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, buildNotification(paused = false))
    }

    private fun stopRecording(fromUser: Boolean, token: Long) {
        val snapshot = synchronized(sessionLock) {
            if (generation != token) return
            if (!capturing.getAndSet(false)) {
                if (fromUser) stopSelfSafely()
                return
            }
            paused.set(false)
            Triple(outputFile, recordThread, audioRecord).also { recordThread = null }
        }
        val (file, thread, recorder) = snapshot
        recorder?.stop() // Unblock a pending read before waiting, never on main.
        try { thread?.join(5_000) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        recorder?.release()
        // WAV recovery/fsync stays outside the lifecycle lock, so a new main-thread
        // command cannot block on disk while an old session is being finalized.
        val terminal = try {
            when {
                file == null -> MicSessionState.Failed(inAppLanguage().getString(R.string.mic_recording_cancelled), session = currentSession)
                thread?.isAlive == true -> MicSessionState.Failed(inAppLanguage().getString(R.string.mic_recording_capture_failed), session = currentSession)
                else -> {
                    val recovered = WavWriter.recoverOrFinalize(file.absolutePath, SAMPLE_RATE_HZ)
                        ?: error("Unable to finalize microphone recording.")
                    MicSessionState.Stopped(recovered.absolutePath, recovered.durationSeconds, recovered.sizeBytes, token)
                }
            }
        } catch (_: Exception) { MicSessionState.Failed(inAppLanguage().getString(R.string.mic_recording_capture_failed), session = currentSession) }
        synchronized(sessionLock) {
            if (generation != token || sessionSequence.get() != token) return
            pcmListener = null
            if (state.value !is MicSessionState.Failed) publish(terminal)
            outputFile = null
            if (audioRecord === recorder) audioRecord = null
            stopSelfSafely()
        }
    }

    private fun stopSelfSafely() {
        try { if (sessionSequence.get() == generation) runtime.removeForeground(this) } catch (_: Exception) { /* Still stop the pending service request. */ }
        finally { stopSelf(latestStartId) }
        if (sessionSequence.get() == generation && state.value !is MicSessionState.Stopped && state.value !is MicSessionState.Failed) publish(MicSessionState.Idle)
    }

    private fun buildNotification(paused: Boolean): Notification {
        fun command(action: String) = Intent(this, MicRecordingService::class.java).setAction(action)
            .setData(android.net.Uri.parse("velum-mic:${generation}:$action"))
            .putExtra(EXTRA_SESSION_TOKEN, generation).putExtra(EXTRA_PATH, currentSession?.absolutePath)
        val stopIntent = PendingIntent.getService(
            this,
            1,
            command(ACTION_STOP),
            pendingFlags(),
        )
        val toggleIntent = PendingIntent.getService(
            this,
            2,
            command(if (paused) ACTION_RESUME else ACTION_PAUSE),
            pendingFlags(),
        )
        val openApp = packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            PendingIntent.getActivity(this, 0, launch, pendingFlags())
        }
        val title = inAppLanguage().getString(
            if (paused) R.string.mic_recording_paused_title else R.string.mic_recording_title,
        )
        val body = inAppLanguage().getString(
            if (paused) R.string.mic_recording_paused_body else R.string.mic_recording_body,
        )
        val toggleLabel = inAppLanguage().getString(
            if (paused) R.string.mic_recording_resume else R.string.mic_recording_pause,
        )
        val stopLabel = inAppLanguage().getString(R.string.mic_recording_stop)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(R.drawable.ic_stat_velum)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, toggleLabel, toggleIntent)
            .addAction(0, stopLabel, stopIntent)
            .build()
    }

    private fun pendingFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    private fun publish(next: MicSessionState) {
        if (next !is MicSessionState.Failed) _failures.value = null
        _state.value = next
        if (next is MicSessionState.Failed) _failures.value = next.message
    }

    companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val CHANNEL_ID = "velum_mic_recording"
        const val NOTIFICATION_ID = 0x56454C01 // "VEL\u0001"
        const val ACTION_START = "dev.velum.app.mic.START"
        const val ACTION_PAUSE = "dev.velum.app.mic.PAUSE"
        const val ACTION_RESUME = "dev.velum.app.mic.RESUME"
        const val ACTION_MARK = "dev.velum.app.mic.MARK"
        const val ACTION_STOP = "dev.velum.app.mic.STOP"
        const val EXTRA_PATH = "path"
        const val EXTRA_SESSION_TOKEN = "session_token"
        const val EXTRA_INPUT_SOURCE = "input_source"
        const val EXTRA_MARK_LABEL = "mark_label"
        private const val SYNC_EVERY_BYTES = 32_000L // ~1s at 16 kHz mono PCM16

        private val nextSessionToken = AtomicLong()
        private val sessionSequence = AtomicLong()
        private val cancelledThrough = AtomicLong()
        @Volatile private var currentService: MicRecordingService? = null
        fun newSession(path: String) = MicSessionIdentity(nextSessionToken.incrementAndGet(), path)
        private val _failures = MutableStateFlow<String?>(null)
        val failures: StateFlow<String?> = _failures.asStateFlow()
        private val _state = MutableStateFlow<MicSessionState>(MicSessionState.Idle)
        val state: StateFlow<MicSessionState> = _state.asStateFlow()

        private val markersLock = Any()
        private val sessionMarkers = ArrayList<MicMarker>()

        fun snapshotMarkers(): List<MicMarker> = synchronized(markersLock) { sessionMarkers.toList() }

        private data class PcmBinding(val session: MicSessionIdentity, val listener: (ByteArray) -> Unit)
        @Volatile private var pcmBinding: PcmBinding? = null
        var pcmListener: ((ByteArray) -> Unit)?
            get() = pcmBinding?.listener
            set(value) { pcmBinding = if (value == null) null else state.value.session?.let { PcmBinding(it, value) } }

        fun attachPcm(session: MicSessionIdentity, listener: (ByteArray) -> Unit): Boolean {
            val service = currentService ?: return false
            return synchronized(service.sessionLock) {
                if (!service.active(session.token) || service.currentSession != session) false
                else { pcmBinding = PcmBinding(session, listener); true }
            }
        }
        fun detachPcm(session: MicSessionIdentity, listener: (ByteArray) -> Unit) {
            val service = currentService ?: return
            synchronized(service.sessionLock) {
                val binding = pcmBinding
                if (binding?.session == session && binding.listener === listener) pcmBinding = null
            }
        }

        fun ensureNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            // Always (re)create: for an existing channel Android only updates the name and description, which
            // is how a change of the in-app language reaches the channel list.
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.inAppLanguage().getString(R.string.mic_recording_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.inAppLanguage().getString(R.string.mic_recording_channel_desc)
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }

        fun start(
            context: Context,
            session: MicSessionIdentity,
            inputSource: MicInputSource = MicInputSource.Mic,
        ) {
            ensureNotificationChannel(context)
            val intent = Intent(context, MicRecordingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PATH, session.absolutePath)
                .putExtra(EXTRA_SESSION_TOKEN, session.token)
                .putExtra(EXTRA_INPUT_SOURCE, inputSource.name)
            ContextCompat.startForegroundService(context, intent)
        }

        fun resolveAudioSource(input: MicInputSource): Int = when (input) {
            MicInputSource.Mic -> MediaRecorder.AudioSource.MIC
            MicInputSource.VoiceCommunication -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
            MicInputSource.Unprocessed ->
                if (Build.VERSION.SDK_INT >= 24) {
                    MediaRecorder.AudioSource.UNPROCESSED
                } else {
                    MediaRecorder.AudioSource.MIC
                }
            MicInputSource.Camcorder -> MediaRecorder.AudioSource.CAMCORDER
        }

        private fun send(context: Context, action: String, session: MicSessionIdentity, label: String = "") {
            context.startService(Intent(context, MicRecordingService::class.java).setAction(action)
                .putExtra(EXTRA_SESSION_TOKEN, session.token).putExtra(EXTRA_PATH, session.absolutePath)
                .putExtra(EXTRA_MARK_LABEL, label))
        }
        fun pause(context: Context, session: MicSessionIdentity) = send(context, ACTION_PAUSE, session)
        fun resume(context: Context, session: MicSessionIdentity) = send(context, ACTION_RESUME, session)
        fun mark(context: Context, session: MicSessionIdentity, label: String = "") = send(context, ACTION_MARK, session, label)
        fun stop(context: Context, session: MicSessionIdentity) = send(context, ACTION_STOP, session)

        /** Cancellation applies only to this request, including cancellation before service delivery. */
        fun cancelPendingStart(context: Context, session: MicSessionIdentity) {
            cancelledThrough.updateAndGet { maxOf(it, session.token) }
            val service = currentService ?: return
            synchronized(service.sessionLock) {
                if (service.currentSession == session) service.failAndStop(session.token, R.string.mic_recording_cancelled)
            }
        }

        fun resetIdle() {
            _failures.value = null
            _state.value = MicSessionState.Idle
        }
    }
}
