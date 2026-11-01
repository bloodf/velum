package dev.velum.app.platform

import android.Manifest
import android.app.Notification
import android.app.Service
import android.media.AudioFormat
import android.media.AudioRecord
import androidx.annotation.RequiresPermission
import androidx.core.app.ServiceCompat
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Android boundaries; unit tests supply queued workers and synthetic audio only. */
internal interface MicServiceRuntime {
    fun promote(service: Service, notification: Notification, type: Int)
    fun removeForeground(service: Service)
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun createInput(source: Int): MicAudioInput
    fun launch(name: String, block: () -> Unit): Thread?
}

internal interface MicAudioInput {
    val initialized: Boolean
    val recording: Boolean
    val bufferSize: Int
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start()
    fun read(buffer: ByteArray): Int
    fun stop()
    fun release()
}

internal object AndroidMicServiceRuntime : MicServiceRuntime {
    override fun promote(service: Service, notification: Notification, type: Int) =
        ServiceCompat.startForeground(service, MicRecordingService.NOTIFICATION_ID, notification, type)
    override fun removeForeground(service: Service) =
        ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE)

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun createInput(source: Int): MicAudioInput {
        val size = AudioRecord.getMinBufferSize(
            MicRecordingService.SAMPLE_RATE_HZ, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        check(size > 0) { "AudioRecord buffer unavailable." }
        return AndroidMicAudioInput(
            AudioRecord(source, MicRecordingService.SAMPLE_RATE_HZ, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, size * 2), size,
        )
    }
    override fun launch(name: String, block: () -> Unit): Thread = thread(name = name, isDaemon = true, block = block)
}

private class AndroidMicAudioInput(private val record: AudioRecord, override val bufferSize: Int) : MicAudioInput {
    override val initialized: Boolean get() = record.state == AudioRecord.STATE_INITIALIZED
    override val recording: Boolean get() = record.recordingState == AudioRecord.RECORDSTATE_RECORDING
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun start() = record.startRecording()
    override fun read(buffer: ByteArray): Int = record.read(buffer, 0, buffer.size)
    override fun stop() = record.stop()
    override fun release() = record.release()
}

/** Stop unblocks reads; release also covers partially initialized or failed-start inputs. */
internal class ManagedMicInput(val input: MicAudioInput) {
    private val stopped = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    private var startAttempted = false
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        startAttempted = true
        input.start()
        check(input.recording) { "AudioRecord did not start." }
    }
    fun stop() {
        if (startAttempted && stopped.compareAndSet(false, true)) {
            try { input.stop() } catch (_: Exception) { /* Release must still run. */ }
        }
    }
    fun release() {
        stop()
        if (released.compareAndSet(false, true)) {
            try { input.release() } catch (_: Exception) { /* Terminal cleanup remains best effort. */ }
        }
    }
}
