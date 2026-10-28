package dev.velum.app.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.velum.app.R
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
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
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var recordThread: Thread? = null
    @Volatile private var outputFile: File? = null
    @Volatile private var bytesWritten: Long = 0
    @Volatile private var startedAtElapsedMs: Long = 0
    @Volatile private var pausedAccumulatedMs: Long = 0
    @Volatile private var pauseStartedElapsedMs: Long = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val path = intent.getStringExtra(EXTRA_PATH)
                if (path.isNullOrBlank()) {
                    publish(MicSessionState.Failed("Missing output path"))
                    stopSelf()
                    return START_NOT_STICKY
                }
                val sourceName = intent.getStringExtra(EXTRA_INPUT_SOURCE)
                    ?: MicInputSource.Mic.name
                val input = runCatching { MicInputSource.valueOf(sourceName) }
                    .getOrDefault(MicInputSource.Mic)
                // Must promote to FGS before any disk / AudioRecord work (timeout crash).
                if (!promoteToForeground()) {
                    publish(MicSessionState.Failed("Already capturing"))
                    return START_NOT_STICKY
                }
                thread(name = "velum-mic-init", isDaemon = true) {
                    beginRecordingAfterForeground(path, input)
                }
            }
            ACTION_PAUSE -> pauseRecording()
            ACTION_RESUME -> resumeRecording()
            ACTION_MARK -> {
                val label = intent.getStringExtra(EXTRA_MARK_LABEL).orEmpty()
                dropMarker(label)
            }
            ACTION_STOP -> {
                // Join + finalize off main — onStartCommand must not block.
                thread(name = "velum-mic-stop", isDaemon = true) {
                    stopRecording(fromUser = true)
                }
            }
            else -> Unit
        }
        // NOT_STICKY: process death is recovered via WavWriter.recoverOrFinalize, not sticky restart.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (capturing.get()) {
            // Best-effort finalize without blocking the main destroy path on join.
            thread(name = "velum-mic-destroy-stop", isDaemon = true) {
                stopRecording(fromUser = false)
            }
        }
        super.onDestroy()
    }

    /**
     * Enter foreground immediately so [startForegroundService] cannot time out.
     * Returns false if a capture session is already active.
     */
    private fun promoteToForeground(): Boolean {
        if (!capturing.compareAndSet(false, true)) {
            return false
        }
        ensureNotificationChannel(this)
        val notification = buildNotification(paused = false)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= 29) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            },
        )
        return true
    }

    private fun beginRecordingAfterForeground(
        absolutePath: String,
        inputSource: MicInputSource = MicInputSource.Mic,
    ) {
        synchronized(markersLock) { sessionMarkers.clear() }
        val file = File(absolutePath)
        file.parentFile?.mkdirs()

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            failAndStop("AudioRecord buffer unavailable ($minBuf)")
            return
        }

        val audioSource = resolveAudioSource(inputSource)
        val recorder = try {
            AudioRecord(
                audioSource,
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2,
            )
        } catch (t: Throwable) {
            failAndStop(t.message ?: t.toString())
            return
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            failAndStop("AudioRecord failed to initialize")
            return
        }

        try {
            RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(0)
                writeWavHeaderTo(raf, dataSize = 0, sampleRateHz = SAMPLE_RATE_HZ)
                raf.fd.sync()
            }
            recorder.startRecording()
        } catch (t: Throwable) {
            recorder.release()
            failAndStop(t.message ?: t.toString())
            return
        }

        // Stop may have raced in during init — tear down and bail.
        if (!capturing.get()) {
            try {
                recorder.stop()
            } catch (_: Throwable) {
            }
            recorder.release()
            return
        }

        outputFile = file
        bytesWritten = 0
        startedAtElapsedMs = SystemClock.elapsedRealtime()
        pausedAccumulatedMs = 0
        pauseStartedElapsedMs = 0
        paused.set(false)
        audioRecord = recorder

        publish(
            MicSessionState.Recording(
                absolutePath = file.absolutePath,
                bytesWritten = 0,
            ),
        )

        recordThread = thread(name = "velum-mic-fgs", isDaemon = true) {
            val buf = ByteArray(minBuf)
            var sinceSync = 0L
            try {
                RandomAccessFile(file, "rw").use { raf ->
                    raf.seek(44)
                    while (capturing.get()) {
                        if (paused.get()) {
                            // Flag-only pause: keep AudioRecord running; skip writes.
                            SystemClock.sleep(50)
                            continue
                        }
                        val n = recorder.read(buf, 0, buf.size)
                        if (n > 0) {
                            raf.write(buf, 0, n)
                            bytesWritten += n.toLong()
                            sinceSync += n.toLong()
                            pcmListener?.invoke(buf.copyOf(n))
                            if (sinceSync >= SYNC_EVERY_BYTES) {
                                raf.seek(0)
                                writeWavHeaderTo(
                                    raf,
                                    dataSize = bytesWritten,
                                    sampleRateHz = SAMPLE_RATE_HZ,
                                )
                                raf.fd.sync()
                                raf.seek(44 + bytesWritten)
                                sinceSync = 0
                                val state = state.value
                                if (state is MicSessionState.Recording) {
                                    publish(state.copy(bytesWritten = bytesWritten))
                                }
                            }
                        } else if (n < 0) {
                            Log.e(TAG, "AudioRecord.read error=$n")
                            break
                        }
                    }
                    raf.seek(0)
                    writeWavHeaderTo(raf, dataSize = bytesWritten, sampleRateHz = SAMPLE_RATE_HZ)
                    raf.fd.sync()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Mic FGS write failed", t)
                capturing.set(false)
                publish(MicSessionState.Failed(t.message ?: t.toString()))
            } finally {
                try {
                    recorder.stop()
                } catch (t: Throwable) {
                    Log.e(TAG, "AudioRecord.stop failed", t)
                }
                recorder.release()
                if (audioRecord === recorder) audioRecord = null
            }
        }
    }

    private fun failAndStop(message: String) {
        capturing.set(false)
        // Don't clobber a terminal Stopped from a racing ACTION_STOP during init.
        if (state.value is MicSessionState.Stopped) {
            stopSelfSafely()
            return
        }
        publish(MicSessionState.Failed(message))
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
        publish(MicSessionState.Paused(absolutePath = path, bytesWritten = bytesWritten))
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
        publish(MicSessionState.Recording(absolutePath = path, bytesWritten = bytesWritten))
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, buildNotification(paused = false))
    }

    private fun stopRecording(fromUser: Boolean) {
        if (!capturing.getAndSet(false)) {
            if (fromUser) stopSelfSafely()
            return
        }
        paused.set(false)
        val file = outputFile
        val thread = recordThread
        recordThread = null
        try {
            thread?.join(5_000)
        } catch (_: InterruptedException) {
        }

        val path = file?.absolutePath
        if (path == null) {
            publish(MicSessionState.Failed("Missing output file"))
            stopSelfSafely()
            return
        }

        val recovered = WavWriter.recoverOrFinalize(path, SAMPLE_RATE_HZ)
            ?: run {
                val size = file.length()
                val activeMs = (
                    SystemClock.elapsedRealtime() - startedAtElapsedMs - pausedAccumulatedMs
                    ).coerceAtLeast(0)
                WavFinish(
                    absolutePath = path,
                    durationSeconds = activeMs / 1000L,
                    sizeBytes = size,
                )
            }

        publish(
            MicSessionState.Stopped(
                absolutePath = recovered.absolutePath,
                durationSeconds = recovered.durationSeconds,
                sizeBytes = recovered.sizeBytes,
            ),
        )
        outputFile = null
        stopSelfSafely()
    }

    private fun stopSelfSafely() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        // Leave Stopped visible briefly for MicCapture waiters; then Idle.
        if (state.value is MicSessionState.Stopped || state.value is MicSessionState.Failed) {
            // MicCapture clears after reading Stopped.
        } else {
            publish(MicSessionState.Idle)
        }
    }

    private fun buildNotification(paused: Boolean): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, MicRecordingService::class.java).setAction(ACTION_STOP),
            pendingFlags(),
        )
        val toggleIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, MicRecordingService::class.java).setAction(
                if (paused) ACTION_RESUME else ACTION_PAUSE,
            ),
            pendingFlags(),
        )
        val openApp = packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            PendingIntent.getActivity(this, 0, launch, pendingFlags())
        }
        val title = getString(
            if (paused) R.string.mic_recording_paused_title else R.string.mic_recording_title,
        )
        val body = getString(
            if (paused) R.string.mic_recording_paused_body else R.string.mic_recording_body,
        )
        val toggleLabel = getString(
            if (paused) R.string.mic_recording_resume else R.string.mic_recording_pause,
        )
        val stopLabel = getString(R.string.mic_recording_stop)
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
        _state.value = next
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
        const val EXTRA_INPUT_SOURCE = "input_source"
        const val EXTRA_MARK_LABEL = "mark_label"
        private const val SYNC_EVERY_BYTES = 32_000L // ~1s at 16 kHz mono PCM16
        private const val TAG = "VelumMicFgs"

        private val _state = MutableStateFlow<MicSessionState>(MicSessionState.Idle)
        val state: StateFlow<MicSessionState> = _state.asStateFlow()

        private val markersLock = Any()
        private val sessionMarkers = ArrayList<MicMarker>()

        fun snapshotMarkers(): List<MicMarker> = synchronized(markersLock) { sessionMarkers.toList() }

        @Volatile
        var pcmListener: ((ByteArray) -> Unit)? = null

        fun ensureNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            val existing = nm.getNotificationChannel(CHANNEL_ID)
            if (existing != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.mic_recording_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.mic_recording_channel_desc)
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }

        fun start(
            context: Context,
            absolutePath: String,
            inputSource: MicInputSource = MicInputSource.Mic,
        ) {
            ensureNotificationChannel(context)
            val intent = Intent(context, MicRecordingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PATH, absolutePath)
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

        fun pause(context: Context) {
            context.startService(
                Intent(context, MicRecordingService::class.java).setAction(ACTION_PAUSE),
            )
        }

        fun resume(context: Context) {
            context.startService(
                Intent(context, MicRecordingService::class.java).setAction(ACTION_RESUME),
            )
        }

        fun mark(context: Context, label: String = "") {
            context.startService(
                Intent(context, MicRecordingService::class.java)
                    .setAction(ACTION_MARK)
                    .putExtra(EXTRA_MARK_LABEL, label),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, MicRecordingService::class.java).setAction(ACTION_STOP),
            )
        }

        fun resetIdle() {
            _state.value = MicSessionState.Idle
        }
    }
}

sealed class MicSessionState {
    data object Idle : MicSessionState()
    data class Recording(
        val absolutePath: String,
        val bytesWritten: Long,
    ) : MicSessionState()
    data class Paused(
        val absolutePath: String,
        val bytesWritten: Long,
    ) : MicSessionState()
    data class Stopped(
        val absolutePath: String,
        val durationSeconds: Long,
        val sizeBytes: Long,
    ) : MicSessionState()
    data class Failed(val message: String) : MicSessionState()
}
